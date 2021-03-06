package com.twitter.finagle.factory

import com.twitter.finagle._
import com.twitter.finagle.loadbalancer.LoadBalancerFactory
import com.twitter.finagle.param.{Label, Stats}
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.tracing.Trace
import com.twitter.util._
import java.net.SocketAddress
import scala.collection.immutable

private[finagle] object NamerTracingFilter {
  /**
   * Trace a lookup from [[com.twitter.finagle.Path]] to
   * [[com.twitter.finagle.Name.Bound]] with the given `record` function.
   */
  def trace(
    path: Path,
    baseDtab: Dtab,
    nameTry: Try[Name.Bound],
    record: (String, String) => Unit = Trace.recordBinary
  ): Unit = {
    record("namer.path", path.show)
    record("namer.dtab.base", baseDtab.show)
    // dtab.local is annotated on the client & server tracers.

    nameTry match {
      case Return(name) =>
        val id = name.id match {
          case strId: String => strId
          case pathId: Path => pathId.show
          case _ => name.id.toString
        }
        record("namer.name", id)

      case Throw(exc) => record("namer.failure", exc.getClass.getName)
    }
  }

  implicit val role = Stack.Role("NamerTracer")

  /**
   * Creates a [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.factory.NamerTracingFilter]].
   */
  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Stack.Module2[BindingFactory.BaseDtab, BoundPath, ServiceFactory[Req, Rep]] {
      val role = NamerTracingFilter.role
      val description = "Trace the details of the Namer lookup"
      def make(_baseDtab: BindingFactory.BaseDtab, boundPath: BoundPath, next: ServiceFactory[Req, Rep]) = {
        val BindingFactory.BaseDtab(baseDtab) = _baseDtab
        boundPath match {
          case BoundPath(Some((path, bound))) =>
            new NamerTracingFilter[Req, Rep](path, baseDtab, bound) andThen next
          case _ => next
        }
      }
    }

  /**
   * A class eligible for configuring a [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.factory.NamerTracingFilter]] with a
   * [[com.twitter.finagle.Path]] and [[com.twitter.finagle.Name.Bound]]
   */
  case class BoundPath(boundPath: Option[(Path, Name.Bound)])
  implicit object BoundPath extends Stack.Param[BoundPath] {
    val default = BoundPath(None)
  }
}

/**
 * A filter to trace a lookup from [[com.twitter.finagle.Path]] to
 * [[com.twitter.finagle.Name.Bound]] with the given `record` function.
 */
private[finagle] class NamerTracingFilter[Req, Rep](
    path: Path,
    baseDtab: () => Dtab,
    bound: Name.Bound,
    record: (String, String) => Unit = Trace.recordBinary)
  extends Filter[Req, Rep, Req, Rep] {

  private[this] val nameTry = Return(bound)

  def apply(request: Req, service: Service[Req, Rep]): Future[Rep] = {
    NamerTracingFilter.trace(path, baseDtab(), nameTry, record)
    service(request)
  }
}

/**
 * Proxies requests to the current definiton of 'name', queueing
 * requests while it is pending.
 */
private class DynNameFactory[Req, Rep](
    name: Activity[Name.Bound],
    newService: (Name.Bound, ClientConnection) => Future[Service[Req, Rep]],
    traceNamerFailure: Throwable => Unit)
  extends ServiceFactory[Req, Rep] {

  private sealed trait State
  private case class Pending(q: immutable.Queue[(ClientConnection, Promise[Service[Req, Rep]])])
    extends State
  private case class Named(name: Name.Bound) extends State
  private case class Failed(exc: Throwable) extends State
  private case class Closed() extends State

  private case class NamingException(exc: Throwable) extends Exception(exc)

  @volatile private[this] var state: State = Pending(immutable.Queue.empty)

  private[this] val sub = name.run.changes respond {
    case Activity.Ok(name) => synchronized {
      state match {
        case Pending(q) =>
          state = Named(name)
          for ((conn, p) <- q) p.become(apply(conn))
        case Failed(_) | Named(_) =>
          state = Named(name)
        case Closed() =>
      }
    }

    case Activity.Failed(exc) => synchronized {
      state match {
        case Pending(q) =>
          // wrap the exception in a NamingException, so that it can
          // be recovered for tracing
          for ((_, p) <- q) p.setException(NamingException(exc))
          state = Failed(exc)
        case Failed(_) =>
          // if already failed, just update the exception; the promises
          // must already be satisfied.
          state = Failed(exc)
        case Named(_) | Closed() =>
      }
    }

    case Activity.Pending =>
  }

  def apply(conn: ClientConnection): Future[Service[Req, Rep]] = {
    state match {
      case Named(name) => newService(name, conn)

      // don't trace these, since they're not a namer failure
      case Closed() => Future.exception(new ServiceClosedException)

      case Failed(exc) =>
        traceNamerFailure(exc)
        Future.exception(exc)

      case Pending(_) =>
        applySync(conn) rescue {
          // extract the underlying exception, to trace and return
          case NamingException(exc) =>
            traceNamerFailure(exc)
            Future.exception(exc)
        }
    }
  }

  private[this] def applySync(conn: ClientConnection): Future[Service[Req, Rep]] = synchronized {
    state match {
      case Pending(q) =>
        val p = new Promise[Service[Req, Rep]]
        val el = (conn, p)
        p setInterruptHandler { case exc =>
          synchronized {
            state match {
              case Pending(q) if q contains el =>
                state = Pending(q filter (_ != el))
                p.setException(new CancelledConnectionException(exc))
              case _ =>
            }
          }
        }
        state = Pending(q enqueue el)
        p

      case other => apply(conn)
    }
  }

  def close(deadline: Time) = {
    val prev = synchronized {
      val prev = state
      state = Closed()
      prev
    }
    prev match {
      case Pending(q) =>
        val exc = new ServiceClosedException
        for ((_, p) <- q)
          p.setException(exc)
      case _ =>
    }
    sub.close(deadline)
  }
}

/**
 * A factory that routes to the local binding of the passed-in
 * [[com.twitter.finagle.Name.Path Name.Path]]. It calls `newFactory`
 * to mint a new [[com.twitter.finagle.ServiceFactory
 * ServiceFactory]] for novel name evaluations.
 *
 * A two-level caching scheme is employed for efficiency:
 *
 * First, Name-trees are evaluated by the default evaluation
 * strategy, which produces a set of [[com.twitter.finagle.Name.Bound
 * Name.Bound]]; these name-sets are cached individually so that they
 * can be reused. Should different name-tree evaluations yield the
 * same name-set, they will use the same underlying (cached) factory.
 *
 * Secondly, in order to avoid evaluating names unnecessarily, we
 * also cache the evaluation relative to a [[com.twitter.finagle.Dtab
 * Dtab]]. This is done to short-circuit the evaluation process most
 * of the time (as we expect most requests to share a namer).
 *
 * @bug This is far too complicated, though it seems necessary for
 * efficiency when namers are occasionally overriden.
 *
 * @bug 'isAvailable' has a funny definition.
 */
private[finagle] class BindingFactory[Req, Rep](
    path: Path,
    newFactory: Name.Bound => ServiceFactory[Req, Rep],
    baseDtab: () => Dtab = BindingFactory.DefaultBaseDtab,
    statsReceiver: StatsReceiver = NullStatsReceiver,
    maxNameCacheSize: Int = 8,
    maxNamerCacheSize: Int = 4)
  extends ServiceFactory[Req, Rep] {

  private[this] val tree = NameTree.Leaf(path)

  private[this] val nameCache =
    new ServiceFactoryCache[Name.Bound, Req, Rep](
      bound => newFactory(bound),
      statsReceiver.scope("namecache"),
      maxNameCacheSize)

  private[this] val noBrokersAvailableException =
    new NoBrokersAvailableException(path.show)

  private[this] val dtabCache = {
    val newFactory: Dtab => ServiceFactory[Req, Rep] = { dtab =>
      val namer = dtab orElse Namer.global
      val name: Activity[Name.Bound] = namer.bind(tree).map(_.eval) flatMap {
        case None => Activity.exception(noBrokersAvailableException)
        case Some(set) if set.isEmpty => Activity.exception(noBrokersAvailableException)
        case Some(set) if set.size == 1 => Activity.value(set.head)
        case Some(set) => Activity.value(Name.all(set))
      }

      new DynNameFactory(
        name,
        nameCache.apply,
        exc => NamerTracingFilter.trace(path, baseDtab(), Throw(exc)))
    }

    new ServiceFactoryCache[Dtab, Req, Rep](
      newFactory,
      statsReceiver.scope("dtabcache"),
      maxNamerCacheSize)
  }

  def apply(conn: ClientConnection): Future[Service[Req, Rep]] = {
    val localDtab = Dtab.local
    val service = dtabCache(baseDtab() ++ localDtab, conn)
    if (localDtab.isEmpty) service
    else service rescue {
      case e: NoBrokersAvailableException =>
        Future.exception(new NoBrokersAvailableException(e.name, localDtab))
    }
  }

  def close(deadline: Time) =
    Closable.sequence(dtabCache, nameCache).close(deadline)

  override def isAvailable = dtabCache.isAvailable
}

private[finagle] object BindingFactory {
  val role = Stack.Role("Binding")

  /**
   * A class eligible for configuring a
   * [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.factory.BindingFactory]] with a destination
   * [[com.twitter.finagle.Name]] to bind.
   */
  case class Dest(dest: Name)
  implicit object Dest extends Stack.Param[Dest] {
    val default = Dest(Name.Path(Path.read("/$/fail")))
  }

  val DefaultBaseDtab = () => Dtab.base

  /**
   * A class eligible for configuring a [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.factory.BindingFactory]] with a
   * [[com.twitter.finagle.Dtab]].
   */
  case class BaseDtab(baseDtab: () => Dtab)
  implicit object BaseDtab extends Stack.Param[BaseDtab] {
    val default = BaseDtab(DefaultBaseDtab)
  }

  /**
   * Creates a [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.factory.BindingFactory]]. The module
   * creates a new `ServiceFactory` based on the module above it for
   * each distinct [[com.twitter.finagle.Name.Bound]] resolved from
   * `BindingFactory.Dest` (with caching of previously seen
   * `Name.Bound`s).
   */
  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Stack.Module[ServiceFactory[Req, Rep]] {
      val role = BindingFactory.role
      val description = "Bind destination names to endpoints"
      val parameters = Seq(
        implicitly[Stack.Param[BindingFactory.Dest]],
        implicitly[Stack.Param[Label]],
        implicitly[Stack.Param[Stats]])
      def make(params: Stack.Params, next: Stack[ServiceFactory[Req, Rep]]) = {
        val Label(label) = params[Label]
        val Stats(stats) = params[Stats]
        val Dest(dest) = params[Dest]

        val factory =
          dest match {
            case Name.Bound(addr) =>
              val params1 = (params +
                LoadBalancerFactory.ErrorLabel(label) +
                LoadBalancerFactory.Dest(addr))
              next.make(params1)

            case Name.Path(path) =>
              val BaseDtab(baseDtab) = params[BaseDtab]
              val params1 = params + LoadBalancerFactory.ErrorLabel(path.show)

              def newStack(bound: Name.Bound) =
                next.make(params1 +
                  NamerTracingFilter.BoundPath(Some(path, bound)) +
                  LoadBalancerFactory.Dest(bound.addr))

              new BindingFactory(path, newStack, baseDtab, stats.scope("interpreter"))
          }

        Stack.Leaf(role, factory)
      }
    }
}
