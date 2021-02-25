/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.zylklab.bigdata.nifi.record_converter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processors.standard.AbstractRecordProcessor;
import org.apache.nifi.processors.standard.UpdateRecord;
import org.apache.nifi.record.path.FieldValue;
import org.apache.nifi.record.path.RecordPath;
import org.apache.nifi.record.path.RecordPathResult;
import org.apache.nifi.record.path.util.RecordPathCache;
import org.apache.nifi.record.path.validation.RecordPathPropertyNameValidator;
import org.apache.nifi.serialization.SimpleRecordSchema;
import org.apache.nifi.serialization.record.MapRecord;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.util.DataTypeUtils;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;


@EventDriven
@SideEffectFree
@SupportsBatching
@InputRequirement(Requirement.INPUT_REQUIRED)
@Tags({"update", "record", "generic", "schema", "json", "csv", "avro", "log", "logs", "freeform", "text"})
@CapabilityDescription("Is the same processor as UpdateRecord but if the destination path record name ends with _map the field is coverted from json string to java map, the keys are strings the values also")
@WritesAttributes({
    @WritesAttribute(attribute = "record.index", description = "This attribute provides the current row index and is only available inside the literal value expression."),
    @WritesAttribute(attribute = "record.error.message", description = "This attribute provides on failure the error message encountered by the Reader or Writer.")
})
@SeeAlso({UpdateRecord.class})
public class UpdateRecordWithMap extends AbstractRecordProcessor {
    private static final String FIELD_NAME = "field.name";
    private static final String FIELD_VALUE = "field.value";
    private static final String FIELD_TYPE = "field.type";

    //private static final String RECORD_INDEX = "record.index";

    private volatile RecordPathCache recordPathCache;
    private volatile List<String> recordPaths;

    static final AllowableValue LITERAL_VALUES = new AllowableValue("literal-value", "Literal Value",
        "The value entered for a Property (after Expression Language has been evaluated) is the desired value to update the Record Fields with. Expression Language "
            + "may reference variables 'field.name', 'field.type', and 'field.value' to access information about the field and the value of the field being evaluated.");
    static final AllowableValue RECORD_PATH_VALUES = new AllowableValue("record-path-value", "Record Path Value",
        "The value entered for a Property (after Expression Language has been evaluated) is not the literal value to use but rather is a Record Path "
            + "that should be evaluated against the Record, and the result of the RecordPath will be used to update the Record. Note that if this option is selected, "
            + "and the Record Path results in multiple values for a given Record, the input FlowFile will be routed to the 'failure' Relationship.");
    
    
    static final AllowableValue STRING_VALUE = new AllowableValue("string-type", "string type",
            "Map has string values");
    static final AllowableValue LONG_VALUE = new AllowableValue("long-type", "long type",
            "Map has long values");
    static final AllowableValue FLOAT_VALUE = new AllowableValue("float-type", "float type",
            "Map has float values");

    static final PropertyDescriptor REPLACEMENT_VALUE_STRATEGY = new PropertyDescriptor.Builder()
        .name("replacement-value-strategy")
        .displayName("Replacement Value Strategy")
        .description("Specifies how to interpret the configured replacement values")
        .allowableValues(LITERAL_VALUES, RECORD_PATH_VALUES)
        .defaultValue(LITERAL_VALUES.getValue())
        .expressionLanguageSupported(ExpressionLanguageScope.NONE)
        .required(true)
        .build();
    
    static final PropertyDescriptor MAP_VALUE_TYPES = new PropertyDescriptor.Builder()
            .name("map-values-types")
            .displayName("Map value type")
            .description("Specifies the map value type")
            .allowableValues(STRING_VALUE, LONG_VALUE, FLOAT_VALUE)
            .defaultValue(STRING_VALUE.getValue())
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .required(true)
            .build();

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> properties = new ArrayList<>(super.getSupportedPropertyDescriptors());
        properties.add(REPLACEMENT_VALUE_STRATEGY);
        properties.add(MAP_VALUE_TYPES);
        return properties;
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
            .name(propertyDescriptorName)
            .description("Specifies the value to use to replace fields in the record that match the RecordPath: " + propertyDescriptorName)
            .required(false)
            .dynamic(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(new RecordPathPropertyNameValidator())
            .build();
    }

    @Override
    protected Collection<ValidationResult> customValidate(final ValidationContext validationContext) {
        final boolean containsDynamic = validationContext.getProperties().keySet().stream().anyMatch(PropertyDescriptor::isDynamic);

        if (containsDynamic) {
            return Collections.emptyList();
        }

        return Collections.singleton(new ValidationResult.Builder()
            .subject("User-defined Properties")
            .valid(false)
            .explanation("At least one RecordPath must be specified")
            .build());
    }

    @OnScheduled
    public void createRecordPaths(final ProcessContext context) {
        recordPathCache = new RecordPathCache(context.getProperties().size() * 2);

        final List<String> recordPaths = new ArrayList<>(context.getProperties().size() - 2);
        for (final PropertyDescriptor property : context.getProperties().keySet()) {
            if (property.isDynamic()) {
                recordPaths.add(property.getName());
            }
        }

        this.recordPaths = recordPaths;
    }

    @Override
    //protected Record process(Record record, final FlowFile flowFile, final ProcessContext context, final long count) {
    protected Record process(Record record, FlowFile flowFile, ProcessContext context) {
        final boolean evaluateValueAsRecordPath = context.getProperty(REPLACEMENT_VALUE_STRATEGY).getValue().equals(RECORD_PATH_VALUES.getValue());
        final String type = context.getProperty(MAP_VALUE_TYPES).getValue();

        for (final String recordPathText : recordPaths) {
            final RecordPath recordPath = recordPathCache.getCompiled(recordPathText);
            final RecordPathResult result = recordPath.evaluate(record);

            if (evaluateValueAsRecordPath) {
                final String replacementValue = context.getProperty(recordPathText).evaluateAttributeExpressions(flowFile).getValue();
                final RecordPath replacementRecordPath = recordPathCache.getCompiled(replacementValue);

                // If we have an Absolute RecordPath, we need to evaluate the RecordPath only once against the Record.
                // If the RecordPath is a Relative Path, then we have to evaluate it against each FieldValue.
                if (replacementRecordPath.isAbsolute()) {
                	try {
                		record = processAbsolutePath(replacementRecordPath, result.getSelectedFields(), record);
					} catch (IOException e) {
						super.getLogger().error("Error proceesing map string as map Object",e);
						throw new ProcessException("Error proceesing map string as map Object",e);
					}
                } else {
                	try {
                		record = processRelativePath(replacementRecordPath, result.getSelectedFields(), record);
					} catch (IOException e) {
						super.getLogger().error("Error proceesing map string as map Object",e);
						throw new ProcessException("Error proceesing map string as map Object",e);
					}
                }
            } else {
                final PropertyValue replacementValue = context.getProperty(recordPathText);

                if (replacementValue.isExpressionLanguagePresent()) {
                    final Map<String, String> fieldVariables = new HashMap<>();

                    result.getSelectedFields().forEach(fieldVal -> {
                        fieldVariables.clear();
                        fieldVariables.put(FIELD_NAME, fieldVal.getField().getFieldName());
                        fieldVariables.put(FIELD_VALUE, DataTypeUtils.toString(fieldVal.getValue(), (String) null));
                        fieldVariables.put(FIELD_TYPE, fieldVal.getField().getDataType().getFieldType().name());
                        //fieldVariables.put(RECORD_INDEX, String.valueOf(count));
                        final String evaluatedReplacementVal = replacementValue.evaluateAttributeExpressions(flowFile, fieldVariables).getValue();
                        if(fieldVal.getField().getFieldName().endsWith("_map")) {
            				try {
								fieldVal.updateValue(buildMapFromString(evaluatedReplacementVal, type), RecordFieldType.MAP.getDataType());
							} catch (IOException e) {
								super.getLogger().error("Error proceesing map string as map Object",e);
								throw new ProcessException("Error proceesing map string as map Object",e);
							}
            			} else {
            				fieldVal.updateValue(evaluatedReplacementVal, RecordFieldType.STRING.getDataType());
            			}
                    });
                } else {
                    final String evaluatedReplacementVal = replacementValue.evaluateAttributeExpressions(flowFile).getValue();
                    result.getSelectedFields().forEach(
                    		fieldVal -> {
                    			if(fieldVal.getField().getFieldName().endsWith("_map")) {
                    				try {
										fieldVal.updateValue(buildMapFromString(evaluatedReplacementVal, type), RecordFieldType.MAP.getDataType());
									} catch (IOException e) {
										super.getLogger().error("Error proceesing map string as map Object",e);
										throw new ProcessException("Error proceesing map string as map Object",e);
									}
                    			} else {
                    				fieldVal.updateValue(evaluatedReplacementVal, RecordFieldType.STRING.getDataType());
                    			}
                    		});
                }
            }
        }

        record.incorporateInactiveFields();

        return record;
    }
    
    
    private Map<String, ?> buildMapFromString(String value) throws JsonParseException, JsonMappingException, IOException {
    	return buildMapFromString(value, STRING_VALUE.getValue());    	
    }
    
    private Map<String, ?> buildMapFromString(String value, String type) throws JsonParseException, JsonMappingException, IOException {
    	ObjectMapper objectMapper = new ObjectMapper();
    	if(type.equals(STRING_VALUE.getValue())) {
    		TypeReference<HashMap<String,String>> typeRef = new TypeReference<HashMap<String,String>>() {};
    		return objectMapper.readValue(value, typeRef);
    	} else if(type.equals(LONG_VALUE.getValue())) {
    		TypeReference<HashMap<String,Long>> typeRef = new TypeReference<HashMap<String,Long>>() {};
    		return objectMapper.readValue(value, typeRef);
    	} else if(type.equals(FLOAT_VALUE.getValue())) {
    		TypeReference<HashMap<String,Float>> typeRef = new TypeReference<HashMap<String,Float>>() {};
    		return objectMapper.readValue(value, typeRef);
    	} else {
    		TypeReference<HashMap<String,String>> typeRef = new TypeReference<HashMap<String,String>>() {};
    		return objectMapper.readValue(value, typeRef);
    	}
    	
    }

    private Record processAbsolutePath(final RecordPath replacementRecordPath, final Stream<FieldValue> destinationFields, final Record record) throws JsonParseException, JsonMappingException, IOException {
        final RecordPathResult replacementResult = replacementRecordPath.evaluate(record);
        final List<FieldValue> selectedFields = replacementResult.getSelectedFields().collect(Collectors.toList());
        final List<FieldValue> destinationFieldValues = destinationFields.collect(Collectors.toList());

        return updateRecord(destinationFieldValues, selectedFields, record);
    }

    private Record processRelativePath(final RecordPath replacementRecordPath, final Stream<FieldValue> destinationFields, Record record) throws JsonParseException, JsonMappingException, IOException {
        final List<FieldValue> destinationFieldValues = destinationFields.collect(Collectors.toList());

        for (final FieldValue fieldVal : destinationFieldValues) {
            final RecordPathResult replacementResult = replacementRecordPath.evaluate(record, fieldVal);
            final List<FieldValue> selectedFields = replacementResult.getSelectedFields().collect(Collectors.toList());
            final Object replacementObject = getReplacementObject(selectedFields);
            updateFieldValue(fieldVal, replacementObject);
        }

        return record;
    }

    private Record updateRecord(final List<FieldValue> destinationFields, final List<FieldValue> selectedFields, final Record record) throws JsonParseException, JsonMappingException, IOException {
        if (destinationFields.size() == 1 && !destinationFields.get(0).getParentRecord().isPresent()) {
            final Object replacement = getReplacementObject(selectedFields);
            if (replacement == null) {
                return record;
            }
            if (replacement instanceof Record) {
                return (Record) replacement;
            }

            final FieldValue replacementFieldValue = (FieldValue) replacement;
            if (replacementFieldValue.getValue() instanceof Record) {
                return (Record) replacementFieldValue.getValue();
            }

            final List<RecordField> fields = selectedFields.stream().map(FieldValue::getField).collect(Collectors.toList());
            final RecordSchema schema = new SimpleRecordSchema(fields);
            final Record mapRecord = new MapRecord(schema, new HashMap<>());
            for (final FieldValue selectedField : selectedFields) {
                mapRecord.setValue(selectedField.getField(), selectedField.getValue());
            }

            return mapRecord;
        } else {
            for (final FieldValue fieldVal : destinationFields) {
                final Object replacementObject = getReplacementObject(selectedFields);
                updateFieldValue(fieldVal, replacementObject);
            }
            return record;
        }
    }

    private void updateFieldValue(final FieldValue fieldValue, final Object replacement) throws JsonParseException, JsonMappingException, IOException {
        if (replacement instanceof FieldValue) {
            final FieldValue replacementFieldValue = (FieldValue) replacement;
            if(fieldValue.getField().getFieldName().endsWith("_map")) {
            	fieldValue.updateValue(buildMapFromString(replacementFieldValue.getValue().toString()), replacementFieldValue.getField().getDataType());
            } else {
            	fieldValue.updateValue(replacementFieldValue.getValue(), replacementFieldValue.getField().getDataType());
            }
        } else {
        	if(fieldValue.getField().getFieldName().endsWith("_map")) {
        		fieldValue.updateValue(buildMapFromString(replacement.toString()));
        	} else {
        		fieldValue.updateValue(replacement);
        	}
        }
    }

    private Object getReplacementObject(final List<FieldValue> selectedFields) {
        if (selectedFields.size() > 1) {
            final List<RecordField> fields = selectedFields.stream().map(FieldValue::getField).collect(Collectors.toList());
            final RecordSchema schema = new SimpleRecordSchema(fields);
            final Record record = new MapRecord(schema, new HashMap<>());
            for (final FieldValue fieldVal : selectedFields) {
                record.setValue(fieldVal.getField(), fieldVal.getValue());
            }

            return record;
        }

        if (selectedFields.isEmpty()) {
            return null;
        } else {
            return selectedFields.get(0);
        }
    }
}
