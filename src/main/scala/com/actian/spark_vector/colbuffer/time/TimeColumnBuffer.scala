/*
 * Copyright 2016 Actian Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.actian.spark_vector.colbuffer.time

import com.actian.spark_vector.colbuffer._
import com.actian.spark_vector.colbuffer.util.TimeConversion

import java.nio.ByteBuffer
import java.sql.Timestamp

private case class TimeColumnBufferParams(cbParams: ColumnBufferBuildParams, converter: TimeConversion.TimeConverter, adjustToUTC: Boolean = false)

private[colbuffer] abstract class TimeColumnBuffer(p: TimeColumnBufferParams,  valueWidth: Int) extends
  ColumnBuffer[Timestamp](p.cbParams.name, p.cbParams.maxValueCount, valueWidth, valueWidth, p.cbParams.nullable) {
  override protected def put(source: Timestamp, buffer: ByteBuffer): Unit = {
    if (p.adjustToUTC) {
      TimeConversion.convertLocalTimestampToUTC(source)
    }
    val convertedSource = p.converter.convert(TimeConversion.normalizedTime(source), p.cbParams.scale)
    putConverted(convertedSource, buffer)
  }

  protected def putConverted(converted: Long, buffer: ByteBuffer): Unit
}

private class TimeIntColumnBuffer(p: TimeColumnBufferParams) extends TimeColumnBuffer(p, IntSize) {
  override protected def putConverted(converted: Long, buffer: ByteBuffer): Unit = buffer.putInt(converted.toInt)
}

private class TimeLongColumnBuffer(p: TimeColumnBufferParams) extends TimeColumnBuffer(p, LongSize) {
  override protected def putConverted(converted: Long, buffer: ByteBuffer): Unit = buffer.putLong(converted)
}

private class TimeNZLZConverter extends TimeConversion.TimeConverter {
  override def convert(nanos: Long, scale: Int): Long = TimeConversion.scaledTime(nanos, scale)
}

private class TimeTZConverter extends TimeConversion.TimeConverter {
  private final val TimeMask = 0xFFFFFFFFFFFFF800L

  override def convert(nanos: Long, scale: Int): Long = (TimeConversion.scaledTime(nanos, scale) << 11) & TimeMask
}

/** Builds a `ColumnBuffer` object for `time` (NZ, TZ, LZ) types. */
private[colbuffer] object TimeColumnBuffer extends ColumnBufferBuilder {
  private final val (nzlzIntScaleBounds, nzlzLongScaleBounds) = ((0, 4), (5, 9))
  private final val (tzIntScaleBounds, tzLongScaleBounds) = ((0, 1), (2, 9))

  private val buildNZPartial: PartialFunction[ColumnBufferBuildParams, TimeColumnBufferParams] = {
    case p if p.tpe == TimeNZTypeId1 || p.tpe == TimeNZTypeId2 => TimeColumnBufferParams(p, new TimeNZLZConverter(), true)
  }

  private val buildLZPartial: PartialFunction[ColumnBufferBuildParams, TimeColumnBufferParams] = {
    case p if p.tpe == TimeLZTypeId => TimeColumnBufferParams(p, new TimeNZLZConverter())
  }

  private val buildNZLZ: PartialFunction[ColumnBufferBuildParams, ColumnBuffer[_]] = (buildNZPartial orElse buildLZPartial) andThenPartial {
    case nzlz if isInBounds(nzlz.cbParams.scale, nzlzIntScaleBounds) => new TimeIntColumnBuffer(nzlz)
    case nzlz if isInBounds(nzlz.cbParams.scale, nzlzLongScaleBounds) => new TimeLongColumnBuffer(nzlz)
  }

  private val buildTZPartial: PartialFunction[ColumnBufferBuildParams, TimeColumnBufferParams] = {
    case p if p.tpe == TimeTZTypeId => TimeColumnBufferParams(p, new TimeTZConverter())
  }

  private val buildTZ: PartialFunction[ColumnBufferBuildParams, ColumnBuffer[_]] = buildTZPartial andThenPartial {
    case tz if isInBounds(tz.cbParams.scale, tzIntScaleBounds) => new TimeIntColumnBuffer(tz)
    case tz if isInBounds(tz.cbParams.scale, tzLongScaleBounds) => new TimeLongColumnBuffer(tz)
  }

  override private[colbuffer] val build: PartialFunction[ColumnBufferBuildParams, ColumnBuffer[_]] = buildNZLZ orElse buildTZ
}