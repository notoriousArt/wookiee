package com.webtrends.harness.command

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormatter
import scala.util.Try

trait CommandBeanExtraction {

  // List of parameters to attempt to extract from the bean.
  val CommandBeanExtractParameters = List[CommandBeanExtractParameter[_]]()

  // List of validation steps to apply after parameters have been extracted
  // but before defaults have been added. Each validation should throw an Exception if it fails
  val CommandBeanExtractValidationSteps: Seq[ (Map[String, Any]) => Unit] = Seq.empty

  def extractFromCommandBean[T](bean: CommandBean, fac: (Map[String, Any]) => T): Try[T] = {

    val exceptions = new scala.collection.mutable.ArrayBuffer[Exception]

    Try {
      val extracted = CommandBeanExtractParameters.flatMap { p =>
        if (bean.contains(p.key)) {
          try {
            Some(p.key -> p.extractor(bean(p.key)))
          } catch {
            case ex: Exception =>
              exceptions += new IllegalArgumentException(s"Invalid value for '${p.key}'")
              None
          }
        }
        else {
          None
        }
      }.toMap

      exceptions ++= validate(extracted)

      val defaults = CommandBeanExtractParameters.flatMap { param =>
        param match {
          case p: RequiredCommandBeanExtractParameter[_] if (!bean.contains(p.key)) =>
            exceptions += new IllegalArgumentException(s"Missing required parameter '${p.key}'")
            None
          case p: OptionalCommandBeanExtractParameter[_] if (!bean.contains(p.key)) =>
            try {
              Some(p.key -> p.defaultValue)
            } catch {
              case ex: Exception =>
                exceptions += new IllegalArgumentException(s"Invalid value for '${p.key}'")
                None
            }
          case _ => None
        }
      }

      if (exceptions.nonEmpty) {
        throw new IllegalArgumentException(exceptions.map(_.getMessage).mkString(", "))
      } else {
        fac(extracted ++ defaults)
      }
    }
  }

  private def validate(data: Map[String, Any]): Seq[Exception] = {
    CommandBeanExtractValidationSteps.flatMap { step =>
      try {
        step(data)
        None
      } catch {
        case ex: Exception => Some(ex)
      }
    }
  }
}

sealed trait CommandBeanExtractParameter[T] {
  val key: String
  def extractor(v: AnyRef): T
}

trait RequiredCommandBeanExtractParameter[T] extends CommandBeanExtractParameter[T]

trait OptionalCommandBeanExtractParameter[T] extends CommandBeanExtractParameter[T] {
  def defaultValue: T
}

sealed trait ExtractString extends CommandBeanExtractParameter[String]{
  def extractor(v: AnyRef) = String.valueOf(v)
}

sealed trait ExtractInt extends CommandBeanExtractParameter[Int]{
  def extractor(v: AnyRef): Int = {
    v match {
      case i: Integer => i
      case s: String => s.toInt
      case _ => throw new IllegalArgumentException(s"Invalid integer value for '$key'")
    }
  }
}

sealed trait ExtractDateTime extends CommandBeanExtractParameter[DateTime]{

  val formatter: DateTimeFormatter

  def extractor(v: AnyRef): DateTime = {
    formatter.parseDateTime(String.valueOf(v))
  }
}

case class RequiredStringCommandBeanExtractParameter(key: String) extends RequiredCommandBeanExtractParameter[String]
with ExtractString

case class OptionalStringCommandBeanExtractParameter(key: String, val default: () => String)
  extends OptionalCommandBeanExtractParameter[String] with ExtractString {

  override def defaultValue = default()
}

case class RequiredIntCommandBeanExtractParameter(key: String) extends RequiredCommandBeanExtractParameter[Int]
with ExtractInt

case class OptionalIntCommandBeanExtractParameter(key: String, val default: () => Int)
  extends OptionalCommandBeanExtractParameter[Int] with ExtractInt {

  override def defaultValue = default()
}

case class RequiredDateTimeCommandBeanExtractParameter(key: String, override val formatter: DateTimeFormatter)
  extends RequiredCommandBeanExtractParameter[DateTime] with ExtractDateTime

case class OptionalDateTimeCommandBeanExtractParameter(key: String, val default: () => DateTime, override val formatter: DateTimeFormatter)
  extends OptionalCommandBeanExtractParameter[DateTime] with ExtractDateTime {

  override def defaultValue = default()
}
