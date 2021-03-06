package com.twitter.finagle.factory

import com.twitter.finagle._
import com.twitter.util._
import java.net.{InetSocketAddress, SocketAddress}
import org.junit.runner.RunWith
import org.mockito.Matchers.any
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.{never, times, verify, when}
import org.mockito.stubbing.Answer
import org.mockito.invocation.InvocationOnMock
import org.scalatest.{BeforeAndAfter, FunSuite}
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import com.twitter.finagle.stats._

@RunWith(classOf[JUnitRunner])
class BindingFactoryTest extends FunSuite with MockitoSugar with BeforeAndAfter {
  var saveBase: Dtab = Dtab.empty
  before {
    saveBase = Dtab.base
    Dtab.base = Dtab.read("""
      /test1010=>/$/inet/0/1010
    """)
  }

  after {
    Dtab.base = saveBase
  }

  def anonNamer() = new Namer {
    def lookup(path: Path): Activity[NameTree[Name]] =
      Activity.value(NameTree.Neg)
    def enum(prefix: Path): Activity[Dtab] =
      Activity.exception(new UnsupportedOperationException)
  }

  trait Ctx {
    val imsr = new InMemoryStatsReceiver

    val path = Path.read("/foo/bar")

    var news = 0
    var closes = 0

    val newFactory: Name.Bound => ServiceFactory[Unit, Var[Addr]] =
      bound => new ServiceFactory[Unit, Var[Addr]] {
        news += 1
        def apply(conn: ClientConnection) = Future.value(new Service[Unit, Var[Addr]] {
          def apply(_unit: Unit) = Future.value(bound.addr)
        })

        def close(deadline: Time) = {
          closes += 1
          Future.Done
        }
      }

    val factory = new BindingFactory(
      path,
      newFactory,
      statsReceiver = imsr,
      maxNamerCacheSize = 2,
      maxNameCacheSize = 2)

    def newWith(localDtab: Dtab): Service[Unit, Var[Addr]] = {
      Dtab.unwind {
        Dtab.local = localDtab
        Await.result(factory())
      }
    }
  }

  test("Uses Dtab.base") (new Ctx {
    val n1 = Dtab.read("/foo/bar=>/test1010")
    val s1 = newWith(n1)
    val v1 = Await.result(s1(()))
    assert(v1.sample() === Addr.Bound(new InetSocketAddress(1010)))

    s1.close()
  })

  test("Respects Dtab.base changes after service factory creation") (new Ctx {
    // factory is already created here
    Dtab.base ++= Dtab.read("/test1010=>/$/inet/0/1011")
    val n1 = Dtab.read("/foo/bar=>/test1010")
    val s1 = newWith(n1)
    val v1 = Await.result(s1(()))
    assert(v1.sample() === Addr.Bound(new InetSocketAddress(1011)))

    s1.close()
  })

  test("Includes path in NoBrokersAvailableException") (new Ctx {
    val noBrokers = intercept[NoBrokersAvailableException] {
      Await.result(factory())
    }

    assert(noBrokers.name === "/foo/bar")
    assert(noBrokers.localDtab === Dtab.empty)
  })

  test("Includes path and Dtab.local in NoBrokersAvailableException from name resolution") (new Ctx {
    val localDtab = Dtab.read("/baz=>/quux")

    val noBrokers = intercept[NoBrokersAvailableException] {
      newWith(localDtab)
    }

    assert(noBrokers.name === "/foo/bar")
    assert(noBrokers.localDtab === localDtab)
  })

  test("Includes path and Dtab.local in NoBrokersAvailableException from service creation") {
    val localDtab = Dtab.read("/foo/bar=>/test1010")

    val factory = new BindingFactory(
      Path.read("/foo/bar"),
      newFactory = { addr =>
        new ServiceFactory[Unit, Unit] {
          def apply(conn: ClientConnection) =
            Future.exception(new NoBrokersAvailableException("/foo/bar"))

          def close(deadline: Time) = Future.Done
        }
      })

    val noBrokers = intercept[NoBrokersAvailableException] {
      Dtab.unwind {
        Dtab.local = localDtab
        Await.result(factory())
      }
    }

    assert(noBrokers.name === "/foo/bar")
    assert(noBrokers.localDtab === localDtab)
  }

  test("Caches namers") (new Ctx {
    val n1 = Dtab.read("/foo/bar=>/$/inet/0/1")
    val n2 = Dtab.read("/foo/bar=>/$/inet/0/2")
    val n3 = Dtab.read("/foo/bar=>/$/inet/0/3")
    val n4 = Dtab.read("/foo/bar=>/$/inet/0/4")

    assert(news === 0)
    Await.result(newWith(n1).close() before newWith(n1).close())
    assert(news === 1)
    assert(closes === 0)

    val s2 = newWith(n2)
    assert(news === 2)
    assert(closes === 0)

    // This should evict n1
    val s3 = newWith(n3)
    assert(news === 3)
    assert(closes === 1)

    // n2, n3 are outstanding, so additional requests
    // should hit the one-shot path.
    val s1 = newWith(n1)
    assert(news === 4)
    assert(closes === 1)
    // Closing this should close the factory immediately.
    s1.close()
    assert(closes === 2)

    Await.result(newWith(n2).close() before newWith(n3).close())
    assert(news === 4)
    assert(closes === 2)
  })

  test("Caches names") (new Ctx {
    val n1 = Dtab.read("/foo/bar=>/$/inet/0/1; /bar/baz=>/$/nil")
    val n2 = Dtab.read("/foo/bar=>/$/inet/0/1")
    val n3 = Dtab.read("/foo/bar=>/$/inet/0/2")
    val n4 = Dtab.read("/foo/bar=>/$/inet/0/3")

    assert(news === 0)
    Await.result(newWith(n1).close() before newWith(n1).close())
    assert(news === 1)
    assert(closes === 0)

    Await.result(newWith(n2).close())
    assert(news === 1)
    assert(closes === 0)

    Await.result(newWith(n3).close())
    assert(news === 2)
    assert(closes === 0)

    Await.result(newWith(n4).close())
    assert(news === 3)
    assert(closes === 1)

    Await.result(newWith(n3).close())
    assert(news === 3)
    assert(closes === 1)

    Await.result(newWith(n1).close())
    assert(news === 4)
    assert(closes === 2)

    Await.result(newWith(n2).close())
    assert(news === 4)
    assert(closes === 2)
  })
}

@RunWith(classOf[JUnitRunner])
class NamerTracingFilterTest extends FunSuite {
   private trait Ctx {
    var records = Seq.empty[(String, String)]
    def record(key: String, value: String) {
      records :+= key -> value
    }

    val addr = RandomSocket.nextAddress()
    val path = Path.read("/foo")

    val baseDtab = () => Dtab.read("/foo => /bar")
    val localDtab = Dtab.read("/bar => /baz")

    def mkName(id: Any) = Name.Bound(Var(Addr.Bound(addr)), id)

    def run(f: => Unit) {
      Dtab.unwind {
        Dtab.local = localDtab
        f
      }
    }

    def verifyRecord(nameOrFailure: Either[String, String]) {
      val expected = Seq(
        "namer.path" -> "/foo",
        "namer.dtab.base" -> "/foo=>/bar",
        nameOrFailure match {
          case Left(id) => "namer.name" -> id
          case Right(failure) => "namer.failure" -> failure
        }
      )
      expectResult(expected)(records)
    }
  }

  test("NamerTracingFilter.trace with string id")(new Ctx {
    run {
      NamerTracingFilter.trace(path, baseDtab(), Return(mkName("dat-name")), record)
      verifyRecord(Left("dat-name"))
    }
  })

  test("NamerTracingFilter.trace name with path id")(new Ctx {
    run {
      NamerTracingFilter.trace(path, baseDtab(), Return(mkName(Path.read("/foo/bar/baz"))), record)
      verifyRecord(Left("/foo/bar/baz"))
    }
  })

  test("NamerTracingFilter.trace name with object id")(new Ctx {
    run {
      NamerTracingFilter.trace(path, baseDtab(), Return(mkName(Some("foo"))), record)
      verifyRecord(Left("Some(foo)"))
    }
  })

  test("NamerTracingFilter.trace throwable")(new Ctx {
    run {
      NamerTracingFilter.trace(path, baseDtab(), Throw(new RuntimeException), record)
      verifyRecord(Right("java.lang.RuntimeException"))
    }
  })

  test("NamerTracingFilter.apply trace path/name with string id")(new Ctx {
    run {
      val filter = new NamerTracingFilter[Int, Int](path, baseDtab, mkName("dat-name"), record)
      val service = filter andThen Service.mk[Int, Int](Future.value(_))
      Await.result(service(3))
      verifyRecord(Left("dat-name"))
    }
  })
}

@RunWith(classOf[JUnitRunner])
class DynNameFactoryTest extends FunSuite with MockitoSugar {
  private trait Ctx {
    val newService = mock[(Name.Bound, ClientConnection) => Future[Service[String, String]]]
    val svc = mock[Service[String, String]]
    val (name, namew) = Activity[Name.Bound]()
    val traceNamerFailure = mock[Throwable => Unit]
    val dyn = new DynNameFactory[String, String](name, newService, traceNamerFailure)
  }

  test("queue requests until name is nonpending (ok)")(new Ctx {
    when(newService(any[Name.Bound], any[ClientConnection])).thenReturn(Future.value(svc))

    val f1, f2 = dyn()
    assert(!f1.isDefined)
    assert(!f2.isDefined)

    namew.notify(Return(Name.empty))

    assert(f1.poll === Some(Return(svc)))
    assert(f2.poll === Some(Return(svc)))

    Await.result(f1)("foo")
    Await.result(f1)("bar")
    Await.result(f2)("baz")

    verify(traceNamerFailure, times(0))(any[Throwable])
  })

  test("queue requests until name is nonpending (fail)")(new Ctx {
    when(newService(any[Name.Bound], any[ClientConnection])).thenReturn(Future.never)

    val f1, f2 = dyn()
    assert(!f1.isDefined)
    assert(!f2.isDefined)

    val exc = new Exception
    namew.notify(Throw(exc))

    assert(f1.poll === Some(Throw(exc)))
    assert(f2.poll === Some(Throw(exc)))
    verify(traceNamerFailure, times(2))(exc)
  })

  test("dequeue interrupted requests")(new Ctx {
    when(newService(any[Name.Bound], any[ClientConnection])).thenReturn(Future.never)

    val f1, f2 = dyn()
    assert(!f1.isDefined)
    assert(!f2.isDefined)

    val exc = new Exception
    f1.raise(exc)

    f1.poll match {
      case Some(Throw(cce: CancelledConnectionException)) =>
        assert(cce.getCause === exc)
        // no throw for cancel
        verify(traceNamerFailure, times(0))(any[Throwable])
      case _ => fail()
    }
    assert(f2.poll === None)

    namew.notify(Return(Name.empty))
    assert(f2.poll === None)
  })
}
