/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.data.parquet;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.Schema;
import org.apache.iceberg.parquet.ParquetSchemaUtil;
import org.apache.iceberg.parquet.ParquetValueReader;
import org.apache.iceberg.parquet.ParquetValueReaders;
import org.apache.iceberg.parquet.TypeWithSchemaVisitor;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.types.Types;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.DecimalLogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

/**
 * @deprecated since 1.8.0, will be made package-private in 1.9.0
 */
@Deprecated
public abstract class BaseParquetReaders<T> {
  protected BaseParquetReaders() {}

  protected ParquetValueReader<T> createReader(Schema expectedSchema, MessageType fileSchema) {
    return createReader(expectedSchema, fileSchema, ImmutableMap.of());
  }

  @SuppressWarnings("unchecked")
  protected ParquetValueReader<T> createReader(
      Schema expectedSchema, MessageType fileSchema, Map<Integer, ?> idToConstant) {
    if (ParquetSchemaUtil.hasIds(fileSchema)) {
      return (ParquetValueReader<T>)
          TypeWithSchemaVisitor.visit(
              expectedSchema.asStruct(), fileSchema, new ReadBuilder(fileSchema, idToConstant));
    } else {
      return (ParquetValueReader<T>)
          TypeWithSchemaVisitor.visit(
              expectedSchema.asStruct(),
              fileSchema,
              new FallbackReadBuilder(fileSchema, idToConstant));
    }
  }

  protected abstract ParquetValueReader<T> createStructReader(
      List<Type> types, List<ParquetValueReader<?>> fieldReaders, Types.StructType structType);

  protected ParquetValueReader<?> fixedReader(ColumnDescriptor desc) {
    return new GenericParquetReaders.FixedReader(desc);
  }

  protected ParquetValueReader<?> dateReader(ColumnDescriptor desc) {
    return new GenericParquetReaders.DateReader(desc);
  }

  protected ParquetValueReader<?> timeReader(
      ColumnDescriptor desc, LogicalTypeAnnotation.TimeUnit unit) {
    switch (unit) {
      case MICROS:
        return new GenericParquetReaders.TimeReader(desc);
      case MILLIS:
        return new GenericParquetReaders.TimeMillisReader(desc);
      default:
        throw new UnsupportedOperationException("Unsupported unit for time: " + unit);
    }
  }

  protected ParquetValueReader<?> timestampReader(
      ColumnDescriptor desc, LogicalTypeAnnotation.TimeUnit unit, boolean isAdjustedToUTC) {
    if (desc.getPrimitiveType().getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.INT96) {
      return new GenericParquetReaders.TimestampInt96Reader(desc);
    }

    switch (unit) {
      case MICROS:
        return isAdjustedToUTC
            ? new GenericParquetReaders.TimestamptzReader(desc)
            : new GenericParquetReaders.TimestampReader(desc);
      case MILLIS:
        return isAdjustedToUTC
            ? new GenericParquetReaders.TimestamptzMillisReader(desc)
            : new GenericParquetReaders.TimestampMillisReader(desc);
      default:
        throw new UnsupportedOperationException("Unsupported unit for timestamp: " + unit);
    }
  }

  protected Object convertConstant(org.apache.iceberg.types.Type type, Object value) {
    return value;
  }

  private class FallbackReadBuilder extends ReadBuilder {
    private FallbackReadBuilder(MessageType type, Map<Integer, ?> idToConstant) {
      super(type, idToConstant);
    }

    @Override
    public ParquetValueReader<?> message(
        Types.StructType expected, MessageType message, List<ParquetValueReader<?>> fieldReaders) {
      // the top level matches by ID, but the remaining IDs are missing
      return super.struct(expected, message, fieldReaders);
    }

    @Override
    public ParquetValueReader<?> struct(
        Types.StructType expected, GroupType struct, List<ParquetValueReader<?>> fieldReaders) {
      // the expected struct is ignored because nested fields are never found when the
      List<ParquetValueReader<?>> newFields =
          Lists.newArrayListWithExpectedSize(fieldReaders.size());
      List<Type> types = Lists.newArrayListWithExpectedSize(fieldReaders.size());
      List<Type> fields = struct.getFields();
      for (int i = 0; i < fields.size(); i += 1) {
        ParquetValueReader<?> fieldReader = fieldReaders.get(i);
        if (fieldReader != null) {
          Type fieldType = fields.get(i);
          int fieldD = type().getMaxDefinitionLevel(path(fieldType.getName())) - 1;
          newFields.add(ParquetValueReaders.option(fieldType, fieldD, fieldReader));
          types.add(fieldType);
        }
      }

      return createStructReader(types, newFields, expected);
    }
  }

  private class LogicalTypeAnnotationParquetValueReaderVisitor
      implements LogicalTypeAnnotation.LogicalTypeAnnotationVisitor<ParquetValueReader<?>> {

    private final ColumnDescriptor desc;
    private final org.apache.iceberg.types.Type.PrimitiveType expected;
    private final PrimitiveType primitive;

    LogicalTypeAnnotationParquetValueReaderVisitor(
        ColumnDescriptor desc,
        org.apache.iceberg.types.Type.PrimitiveType expected,
        PrimitiveType primitive) {
      this.desc = desc;
      this.expected = expected;
      this.primitive = primitive;
    }

    @Override
    public Optional<ParquetValueReader<?>> visit(
        LogicalTypeAnnotation.StringLogicalTypeAnnotation stringLogicalType) {
      return Optional.of(new ParquetValueReaders.StringReader(desc));
    }

    @Override
    public Optional<ParquetValueReader<?>> visit(
        LogicalTypeAnnotation.EnumLogicalTypeAnnotation enumLogicalType) {
      return Optional.of(new ParquetValueReaders.StringReader(desc));
    }

    @Override
    public Optional<ParquetValueReader<?>> visit(DecimalLogicalTypeAnnotation decimalLogicalType) {
      switch (primitive.getPrimitiveTypeName()) {
        case BINARY:
        case FIXED_LEN_BYTE_ARRAY:
          return Optional.of(
              new ParquetValueReaders.BinaryAsDecimalReader(desc, decimalLogicalType.getScale()));
        case INT64:
          return Optional.of(
              new ParquetValueReaders.LongAsDecimalReader(desc, decimalLogicalType.getScale()));
        case INT32:
          return Optional.of(
              new ParquetValueReaders.IntegerAsDecimalReader(desc, decimalLogicalType.getScale()));
        default:
          throw new UnsupportedOperationException(
              "Unsupported base type for decimal: " + primitive.getPrimitiveTypeName());
      }
    }

    @Override
    public Optional<ParquetValueReader<?>> visit(
        LogicalTypeAnnotation.DateLogicalTypeAnnotation dateLogicalType) {
      return Optional.of(dateReader(desc));
    }

    @Override
    public Optional<ParquetValueReader<?>> visit(
        LogicalTypeAnnotation.TimeLogicalTypeAnnotation timeLogicalType) {
      return Optional.of(timeReader(desc, timeLogicalType.getUnit()));
    }

    @Override
    public Optional<ParquetValueReader<?>> visit(
        LogicalTypeAnnotation.TimestampLogicalTypeAnnotation timestampLogicalType) {
      return Optional.of(
          timestampReader(
              desc,
              timestampLogicalType.getUnit(),
              ((Types.TimestampType) expected).shouldAdjustToUTC()));
    }

    @Override
    public Optional<ParquetValueReader<?>> visit(
        LogicalTypeAnnotation.IntLogicalTypeAnnotation intLogicalType) {
      if (intLogicalType.getBitWidth() == 64) {
        return Optional.of(new ParquetValueReaders.UnboxedReader<>(desc));
      }
      return (expected.typeId() == org.apache.iceberg.types.Type.TypeID.LONG)
          ? Optional.of(new ParquetValueReaders.IntAsLongReader(desc))
          : Optional.of(new ParquetValueReaders.UnboxedReader<>(desc));
    }

    @Override
    public Optional<ParquetValueReader<?>> visit(
        LogicalTypeAnnotation.JsonLogicalTypeAnnotation jsonLogicalType) {
      return Optional.of(new ParquetValueReaders.StringReader(desc));
    }

    @Override
    public Optional<ParquetValueReader<?>> visit(
        LogicalTypeAnnotation.BsonLogicalTypeAnnotation bsonLogicalType) {
      return Optional.of(new ParquetValueReaders.BytesReader(desc));
    }

    @Override
    public Optional<ParquetValueReader<?>> visit(
        LogicalTypeAnnotation.UUIDLogicalTypeAnnotation uuidLogicalType) {
      return Optional.of(ParquetValueReaders.uuids(desc));
    }
  }

  private class ReadBuilder extends TypeWithSchemaVisitor<ParquetValueReader<?>> {
    private final MessageType type;
    private final Map<Integer, ?> idToConstant;

    private ReadBuilder(MessageType type, Map<Integer, ?> idToConstant) {
      this.type = type;
      this.idToConstant = idToConstant;
    }

    @Override
    public ParquetValueReader<?> message(
        Types.StructType expected, MessageType message, List<ParquetValueReader<?>> fieldReaders) {
      return struct(expected, message.asGroupType(), fieldReaders);
    }

    @Override
    public ParquetValueReader<?> struct(
        Types.StructType expected, GroupType struct, List<ParquetValueReader<?>> fieldReaders) {
      // match the expected struct's order
      Map<Integer, ParquetValueReader<?>> readersById = Maps.newHashMap();
      Map<Integer, Type> typesById = Maps.newHashMap();
      Map<Integer, Integer> maxDefinitionLevelsById = Maps.newHashMap();
      List<Type> fields = struct.getFields();
      for (int i = 0; i < fields.size(); i += 1) {
        ParquetValueReader<?> fieldReader = fieldReaders.get(i);
        if (fieldReader != null) {
          Type fieldType = fields.get(i);
          int fieldD = type.getMaxDefinitionLevel(path(fieldType.getName())) - 1;
          int id = fieldType.getId().intValue();
          readersById.put(id, ParquetValueReaders.option(fieldType, fieldD, fieldReader));
          typesById.put(id, fieldType);
          if (idToConstant.containsKey(id)) {
            maxDefinitionLevelsById.put(id, fieldD);
          }
        }
      }

      List<Types.NestedField> expectedFields =
          expected != null ? expected.fields() : ImmutableList.of();
      List<ParquetValueReader<?>> reorderedFields =
          Lists.newArrayListWithExpectedSize(expectedFields.size());
      List<Type> types = Lists.newArrayListWithExpectedSize(expectedFields.size());
      // Defaulting to parent max definition level
      int defaultMaxDefinitionLevel = type.getMaxDefinitionLevel(currentPath());
      for (Types.NestedField field : expectedFields) {
        int id = field.fieldId();
        ParquetValueReader<?> reader = readersById.get(id);
        if (idToConstant.containsKey(id)) {
          // containsKey is used because the constant may be null
          int fieldMaxDefinitionLevel =
              maxDefinitionLevelsById.getOrDefault(id, defaultMaxDefinitionLevel);
          reorderedFields.add(
              ParquetValueReaders.constant(idToConstant.get(id), fieldMaxDefinitionLevel));
          types.add(null);
        } else if (id == MetadataColumns.ROW_POSITION.fieldId()) {
          reorderedFields.add(ParquetValueReaders.position());
          types.add(null);
        } else if (id == MetadataColumns.IS_DELETED.fieldId()) {
          reorderedFields.add(ParquetValueReaders.constant(false));
          types.add(null);
        } else if (reader != null) {
          reorderedFields.add(reader);
          types.add(typesById.get(id));
        } else if (field.initialDefault() != null) {
          reorderedFields.add(
              ParquetValueReaders.constant(
                  convertConstant(field.type(), field.initialDefault()),
                  maxDefinitionLevelsById.getOrDefault(id, defaultMaxDefinitionLevel)));
          types.add(typesById.get(id));
        } else if (field.isOptional()) {
          reorderedFields.add(ParquetValueReaders.nulls());
          types.add(null);
        } else {
          throw new IllegalArgumentException(
              String.format("Missing required field: %s", field.name()));
        }
      }

      return createStructReader(types, reorderedFields, expected);
    }

    @Override
    public ParquetValueReader<?> list(
        Types.ListType expectedList, GroupType array, ParquetValueReader<?> elementReader) {
      if (expectedList == null) {
        return null;
      }

      String[] repeatedPath = currentPath();

      int repeatedD = type.getMaxDefinitionLevel(repeatedPath) - 1;
      int repeatedR = type.getMaxRepetitionLevel(repeatedPath) - 1;

      Type elementType = ParquetSchemaUtil.determineListElementType(array);
      int elementD = type.getMaxDefinitionLevel(path(elementType.getName())) - 1;

      return new ParquetValueReaders.ListReader<>(
          repeatedD, repeatedR, ParquetValueReaders.option(elementType, elementD, elementReader));
    }

    @Override
    public ParquetValueReader<?> map(
        Types.MapType expectedMap,
        GroupType map,
        ParquetValueReader<?> keyReader,
        ParquetValueReader<?> valueReader) {
      if (expectedMap == null) {
        return null;
      }

      GroupType repeatedKeyValue = map.getFields().get(0).asGroupType();
      String[] repeatedPath = currentPath();

      int repeatedD = type.getMaxDefinitionLevel(repeatedPath) - 1;
      int repeatedR = type.getMaxRepetitionLevel(repeatedPath) - 1;

      Type keyType = repeatedKeyValue.getType(0);
      int keyD = type.getMaxDefinitionLevel(path(keyType.getName())) - 1;
      Type valueType = repeatedKeyValue.getType(1);
      int valueD = type.getMaxDefinitionLevel(path(valueType.getName())) - 1;

      return new ParquetValueReaders.MapReader<>(
          repeatedD,
          repeatedR,
          ParquetValueReaders.option(keyType, keyD, keyReader),
          ParquetValueReaders.option(valueType, valueD, valueReader));
    }

    @Override
    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    public ParquetValueReader<?> primitive(
        org.apache.iceberg.types.Type.PrimitiveType expected, PrimitiveType primitive) {
      if (expected == null) {
        return null;
      }

      ColumnDescriptor desc = type.getColumnDescription(currentPath());

      if (primitive.getLogicalTypeAnnotation() != null) {
        return primitive
            .getLogicalTypeAnnotation()
            .accept(new LogicalTypeAnnotationParquetValueReaderVisitor(desc, expected, primitive))
            .orElseThrow(
                () ->
                    new UnsupportedOperationException(
                        "Unsupported logical type: " + primitive.getLogicalTypeAnnotation()));
      }

      switch (primitive.getPrimitiveTypeName()) {
        case FIXED_LEN_BYTE_ARRAY:
          return fixedReader(desc);
        case BINARY:
          if (expected.typeId() == org.apache.iceberg.types.Type.TypeID.STRING) {
            return new ParquetValueReaders.StringReader(desc);
          } else {
            return new ParquetValueReaders.BytesReader(desc);
          }
        case INT32:
          if (expected.typeId() == org.apache.iceberg.types.Type.TypeID.LONG) {
            return new ParquetValueReaders.IntAsLongReader(desc);
          } else {
            return new ParquetValueReaders.UnboxedReader<>(desc);
          }
        case FLOAT:
          if (expected.typeId() == org.apache.iceberg.types.Type.TypeID.DOUBLE) {
            return new ParquetValueReaders.FloatAsDoubleReader(desc);
          } else {
            return new ParquetValueReaders.UnboxedReader<>(desc);
          }
        case BOOLEAN:
        case INT64:
        case DOUBLE:
          return new ParquetValueReaders.UnboxedReader<>(desc);
        case INT96:
          // Impala & Spark used to write timestamps as INT96 without a logical type. For backwards
          // compatibility we try to read INT96 as timestamps.
          return timestampReader(desc, LogicalTypeAnnotation.TimeUnit.NANOS, true);
        default:
          throw new UnsupportedOperationException("Unsupported type: " + primitive);
      }
    }

    MessageType type() {
      return type;
    }
  }
}
