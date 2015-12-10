package com.sksamuel.avro4s

import java.nio.ByteBuffer
import java.util

import org.apache.avro.generic.GenericData.Record
import org.apache.avro.generic.GenericRecord

import scala.reflect.macros.blackbox

trait AvroSerializer[T] {
  def write(value: T)(implicit s: AvroSchema[T]): Record
}

trait AvroRecordPut[T] {
  def put(name: String, value: T, record: Record): Unit = record.put(name, value)
}

object Writers {

  implicit val StringWriter: AvroRecordPut[String] = new AvroRecordPut[String] {}
  implicit val BigDecimalSchema: AvroRecordPut[BigDecimal] = new AvroRecordPut[BigDecimal] {}
  implicit val DoubleSchema: AvroRecordPut[Double] = new AvroRecordPut[Double] {}
  implicit val FloatSchema: AvroRecordPut[Float] = new AvroRecordPut[Float] {}
  implicit val BooleanSchema: AvroRecordPut[Boolean] = new AvroRecordPut[Boolean] {}
  implicit val IntSchema: AvroRecordPut[Int] = new AvroRecordPut[Int] {}
  implicit val LongSchema: AvroRecordPut[Long] = new AvroRecordPut[Long] {}

  implicit val ByteArrayPut: AvroRecordPut[Array[Byte]] = new AvroRecordPut[Array[Byte]] {
    override def put(name: String, value: Array[Byte], record: Record): Unit = {
      record.put(name, ByteBuffer.wrap(value))
    }
  }

  implicit def OptionPut[T]: AvroRecordPut[Option[T]] = new AvroRecordPut[Option[T]] {
    override def put(name: String, value: Option[T], record: Record): Unit = {
      value.foreach(record.put(name, _))
    }
  }

  implicit def EitherPut[A, B]: AvroRecordPut[Either[A, B]] = new AvroRecordPut[Either[A, B]] {
    override def put(name: String, either: Either[A, B], record: Record): Unit = {
      either.left.foreach(record.put(name, _))
      either.right.foreach(record.put(name, _))
    }
  }

  import scala.collection.JavaConverters._

  implicit object StringMapPut extends AvroRecordPut[Map[String, String]] {
    override def put(name: String, value: Map[String, String], record: Record): Unit = {
      import scala.collection.JavaConverters._
      record.put(name, value.asJava)
    }
  }

  implicit def ArraySchema[S]: AvroRecordPut[Array[S]] = new AvroRecordPut[Array[S]] {
    override def put(name: String, value: Array[S], record: Record): Unit = {
      record.put(name, value.toList.asJavaCollection)
    }
  }

  implicit def ListSchema[S]: AvroRecordPut[List[S]] = new AvroRecordPut[List[S]] {
    override def put(name: String, value: List[S], record: Record): Unit = {
      import scala.collection.JavaConverters._
      record.put(name, value.asJavaCollection)
    }
  }


  class PrimitiveSeqRecordPut[T] extends AvroRecordPut[Seq[T]] {
    override def put(name: String, value: Seq[T], record: Record): Unit = {
      import scala.collection.JavaConverters._
      record.put(name, value.asJavaCollection)
    }
  }

  implicit object StringSeqPut extends PrimitiveSeqRecordPut[String]

  implicit object FloatPut extends PrimitiveSeqRecordPut[Float]

  implicit object DoubleSeqPut extends PrimitiveSeqRecordPut[Double]

  implicit object IntSeqPut extends PrimitiveSeqRecordPut[Int]

  implicit object LongSeqPut extends PrimitiveSeqRecordPut[Long]

  implicit object BooleanSeqPut extends PrimitiveSeqRecordPut[Boolean]

  implicit def SeqSchema[S](implicit s: AvroSchema[S], serializer: AvroSerializer[S]): AvroRecordPut[Seq[S]] = new AvroRecordPut[Seq[S]] {
    override def put(name: String, value: Seq[S], record: Record): Unit = {
      val list = new util.ArrayList[GenericRecord]()
      value.foreach(x => list.add(serializer.write(x)))
      record.put(name, list)
    }
  }

  implicit def IterableSchema[S]: AvroRecordPut[Iterable[S]] = new AvroRecordPut[Iterable[S]] {
    override def put(name: String, value: Iterable[S], record: Record): Unit = {
      import scala.collection.JavaConverters._
      record.put(name, value.toList.asJavaCollection)
    }
  }


  //  implicit def MapPut[V]: AvroRecordPut[Map[String, V]] = new AvroRecordPut[Map[String, V]] {
  //    override def put(name: String, value: Map[String, V], record: Record): Unit = {
  //      import scala.collection.JavaConverters._
  //      record.put(name, value.asJava)
  //    }
  //  }

  def fieldWriter[T](name: String, value: T, record: Record)(implicit s: AvroSchema[T], p: AvroRecordPut[T]): Unit = {
    p.put(name, value, record)
  }

  def impl[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[AvroSerializer[T]] = {

    import c.universe._
    val t = weakTypeOf[T]

    val fields = t.declarations.collectFirst {
      case m: MethodSymbol if m.isPrimaryConstructor => m
    }.get.paramss.head

    val fieldWrites: Seq[Tree] = fields.map { f =>
      val termName = f.name.toTermName
      val decoded = f.name.decoded
      val sig = f.typeSignature
      q"""{ import com.sksamuel.avro4s.Writers._
            (t: $t, r: org.apache.avro.generic.GenericData.Record) => {
              implicitly[com.sksamuel.avro4s.AvroRecordPut[$sig]].put($decoded, t.$termName, r)
            }
          }
      """
    }

    c.Expr[AvroSerializer[T]](
      q"""
      new com.sksamuel.avro4s.AvroSerializer[$t] {
        import org.apache.avro.generic.GenericData.Record
        import com.sksamuel.avro4s.SchemaMacros._
        override def write(t: $t)(implicit s: com.sksamuel.avro4s.AvroSchema[$t]): org.apache.avro.generic.GenericData.Record = {
         val r = new org.apache.avro.generic.GenericData.Record(s.schema)
         Seq(..$fieldWrites).foreach(fn => fn(t, r))
         r
        }
      }
    """)
  }
}