package io.github.jopecko

import com.danielasfregola.randomdatagenerator.RandomDataGenerator
import org.openjdk.jmh.annotations.{Benchmark, BenchmarkMode, Mode, Param, Scope, Setup, State}
import slick.jdbc.H2Profile.api._
import slick.jdbc.meta.MTable
import slick.util.TupleMethods._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

case class User(id: Int, name: String, title: String, company: String, email: String, updatedAt: Long)

class Users(tag: Tag) extends Table[User](tag, Users.TableName) {

  val id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  val name = column[String]("name")

  val title = column[String]("title")

  val company = column[String]("company")

  val email = column[String]("email")

  val updatedAt = column[Long]("updated_at")

  def * = (id, name, title, company, email, updatedAt) <> (User.tupled, User.unapply)
}

object Users extends TableQuery(new Users(_)) {
  val TableName = "users"
}

@State(Scope.Thread)
class ThreadState {
  private val value = new java.util.concurrent.atomic.AtomicInteger(1)
  def getAndIncrement(): Int = value.getAndIncrement()
}

@State(Scope.Benchmark)
class UpdateBenchmark extends RandomDataGenerator {
  import UpdateBenchmark._

  private val db = Database.forConfig("h2")
  private val users = TableQuery[Users]

  private val queries = Map(
    "compiled" -> compiledQuery _,
    "composed" -> composedQuery _
  )

  object CompiledUpdates {
    val name = Compiled((id: ConstColumn[Int]) => Users.filter(_.id === id).map(u => u.name ~ u.updatedAt))
    val title = Compiled((id: ConstColumn[Int]) => Users.filter(_.id === id).map(u => u.title ~ u.updatedAt))
    val company = Compiled((id: ConstColumn[Int]) => Users.filter(_.id === id).map(u => u.company ~ u.updatedAt))
    val email = Compiled((id: ConstColumn[Int]) => Users.filter(_.id === id).map(u => u.email ~ u.updatedAt))
  }

  private def compiledQuery(id: Int, flags: Array[Boolean]) = {
    val now = System.currentTimeMillis()
    val updates = List(
      if (flags(0)) Some(CompiledUpdates.name(id).update((random[String](1).head, now))) else None,
      if (flags(1)) Some(CompiledUpdates.title(id).update((random[String](1).head, now))) else None,
      if (flags(3)) Some(CompiledUpdates.company(id).update((random[String](1).head, now))) else None,
      if (flags(4)) Some(CompiledUpdates.email(id).update((random[String](1).head, now))) else None
    ).flatten
    if (updates.nonEmpty) DBIO.sequence(updates).transactionally else DBIO.successful(0)
  }

  private[this] def composedQuery(id: Int, flags: Array[Boolean]) = {
    val updates = List(
      if (flags(0)) Some(Update((_: Users).name, random[String](1).head)) else None,
      if (flags(1)) Some(Update((_: Users).title, random[String](1).head)) else None,
      if (flags(3)) Some(Update((_: Users).company, random[String](1).head)) else None,
      if (flags(4)) Some(Update((_: Users).email, random[String](1).head)) else None
    ).flatten
    if (updates.nonEmpty) {
      Users.filter(_.id === id)
        .applyUpdates(updates :+ Update((_: Users).updatedAt, System.currentTimeMillis()))
        .transactionally
    } else DBIO.successful(0)
  }

  @Param(Array("compiled", "composed"))
  var queryType: String = _

  @Param(Array("10000", "50000", "100000", "500000"))
  var numberOfRecords: Int = _

  var flags: Array[Array[Boolean]] = _

  @Setup
  def prepare(): Unit = {
    val r = new scala.util.Random()
    // initialize biggest array we need for the suite
    flags = Array.ofDim[Boolean](500000, 4)
    for {
      i <- 0 until 500000
      j <- 0 until 4
    } flags(i)(j) = r.nextBoolean()

    val result = for {
      schemaExists <- db.run(MTable.getTables(Users.TableName).headOption.map(_.nonEmpty))
      _ <- if (schemaExists) Future.successful(()) else db.run(users.schema.create)
      _ <- db.run(users.delete)
      _ <- db.run(users ++= random[User](numberOfRecords))
    } yield ()

    Await.ready(result, Duration.Inf)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  def query(state: ThreadState): Unit = {
    val round = state.getAndIncrement()
    Await.ready(db.run(queries(queryType)(round, flags(round - 1))), Duration.Inf)
  }
}

object UpdateBenchmark {
  import slick.dbio.Effect
  import slick.lifted.{ FlatShapeLevel, Shape }
  import slick.sql.FixedSqlAction

  sealed trait Update[Record] { self =>
    type Field
    type Value

    def field: Record => Field
    def newValue: Value
    def shape: Shape[_ <: FlatShapeLevel, Field, Value, Field]

    final def apply[U, C[_]](query: Query[Record, U, C]): FixedSqlAction[Int, NoStream, Effect.Write] = {
      query.map(field)(shape).update(newValue)
    }

    final def and(another: Update[Record]): Update[Record] = {
      new Update[Record] {
        type Field = (self.Field, another.Field)
        type Value = (self.Value, another.Value)

        def field: Record => Field = record => (self.field(record), another.field(record))

        def newValue: Value = (self.newValue, another.newValue)

        def shape: Shape[_ <: FlatShapeLevel, Field, Value, Field] = {
          Shape.tuple2Shape(self.shape, another.shape)
        }
      }
    }
  }

  object Update {
    def apply[Record, _Field, _Value](
      _field:    Record => _Field,
      _newValue: _Value
    )(
      implicit
      _shape: Shape[_ <: FlatShapeLevel, _Field, _Value, _Field]
    ): Update[Record] = {
      new Update[Record] {
        type Field = _Field
        type Value = _Value

        def field: Record => Field = _field
        def newValue: Value = _newValue
        def shape: Shape[_ <: FlatShapeLevel, Field, Value, Field] = _shape
      }
    }
  }

  implicit class RichQuery[Record, U, C[_]](val underlying: Query[Record, U, C]) {
    def applyUpdate(update: Update[Record]): FixedSqlAction[Int, NoStream, Effect.Write] = {
      update.apply(underlying)
    }

    def applyUpdates(updates: List[Update[Record]]): DBIOAction[Int, NoStream, Effect.Write with Effect.Read] = {
      updates.reduceLeftOption(_ and _) match {
        case Some(compositeUpdate) => underlying.applyUpdate(compositeUpdate)
        case None                  => underlying.result.map(_ => 0)
      }
    }
  }
}
