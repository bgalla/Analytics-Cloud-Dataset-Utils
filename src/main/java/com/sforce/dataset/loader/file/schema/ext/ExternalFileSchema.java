/*
 * Copyright (c) 2014, salesforce.com, inc.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided 
 * that the following conditions are met:
 * 
 *    Redistributions of source code must retain the above copyright notice, this list of conditions and the 
 *    following disclaimer.
 *  
 *    Redistributions in binary form must reproduce the above copyright notice, this list of conditions and 
 *    the following disclaimer in the documentation and/or other materials provided with the distribution. 
 *    
 *    Neither the name of salesforce.com, inc. nor the names of its contributors may be used to endorse or 
 *    promote products derived from this software without specific prior written permission.
 *  
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED 
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A 
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR 
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED 
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) 
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING 
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.sforce.dataset.loader.file.schema.ext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.supercsv.io.CsvListReader;
import org.supercsv.prefs.CsvPreference;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sforce.dataset.DatasetUtilConstants;
import com.sforce.dataset.util.DatasetUtils;

public class ExternalFileSchema extends com.sforce.dataset.loader.file.schema.ExternalFileSchema {

	private static final String SCHEMA_FILE_SUFFIX = "_schema.json";

	public FileFormat fileFormat; 
	public LinkedList<ObjectType> objects;
	
	public ExternalFileSchema()
	{
		super();
	}

	public ExternalFileSchema(ExternalFileSchema old)
	{
		super();
		if(old != null)
		{
			this.fileFormat = new FileFormat(old.fileFormat);
			this.objects = new LinkedList<ObjectType>();
			if(old.objects!=null)
			{
				for(ObjectType obj:old.objects)
				{
					this.objects.add(new ObjectType(obj));
				}
			}
		}
	}

	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ((fileFormat == null) ? 0 : fileFormat.hashCode());
		result = prime * result + ((objects == null) ? 0 : objects.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		ExternalFileSchema other = (ExternalFileSchema) obj;
		if (fileFormat == null) {
			if (other.fileFormat != null) {
				return false;
			}
		} else if (!fileFormat.equals(other.fileFormat)) {
			return false;
		}
		if (objects == null) {
			if (other.objects != null) {
				return false;
			}
		} else if (!objects.equals(other.objects)) {
			return false;
		}
		return true;
	}

	public static ExternalFileSchema init(File csvFile, Charset fileCharset, PrintStream logger) throws JsonParseException, JsonMappingException, IOException
	{
		ExternalFileSchema newSchema = null;
		//try 
		//{
			ExternalFileSchema userSchema = ExternalFileSchema.load(csvFile, fileCharset, logger);
			ExternalFileSchema autoSchema = ExternalFileSchema.createAutoSchema(csvFile, userSchema, fileCharset, logger);
			
			if(userSchema==null)
			{
				ExternalFileSchema.save(csvFile, autoSchema, logger);
				userSchema = autoSchema; 
			}

			if(userSchema!=null && !userSchema.equals(autoSchema))
			{			
				ExternalFileSchema schema = ExternalFileSchema.merge(userSchema, autoSchema, logger);
				if(!schema.equals(userSchema))
				{
					logger.println("Saving merged schema");
					ExternalFileSchema.save(csvFile, schema, logger);
				}
//				newSchema = ExternalFileSchema.load(csvFile);
				newSchema = schema;
			}else
			{
				newSchema = autoSchema;
			}
		//} catch (Throwable t) {
		//	t.printStackTrace();
		//}
		validateSchema(newSchema, logger);			
		return newSchema;
	}


	
	public static ExternalFileSchema createAutoSchema(File csvFile, ExternalFileSchema userSchema, Charset fileCharset, PrintStream logger) throws IOException
	{
		ExternalFileSchema emd = null;
		String baseName = FilenameUtils.getBaseName(csvFile.getName());
		baseName = createDevName(baseName, "Object", 0);
		String fullyQualifiedName = baseName; 
		//try 
		//{
			if(userSchema!=null)
			{
				if(userSchema.objects!=null && userSchema.objects.size()==1)
				{
					baseName = userSchema.objects.get(0).name;
					//because fully qualified name is used to match auto schema we will use user specified 
					fullyQualifiedName = userSchema.objects.get(0).name; 
				}
			}
		
			DetectFieldTypes detEFT = new DetectFieldTypes();
			LinkedList<FieldType> fields = detEFT.detect(csvFile, userSchema, fileCharset, logger);
			FileFormat fileFormat = new FileFormat();

			ObjectType od = new ObjectType();
			od.name = baseName;
			od.fullyQualifiedName = fullyQualifiedName;
			od.label = baseName;
			od.description = baseName;
			od.connector = "SalesforceAnalyticsCloudDatasetLoader";
//			od.isPrimaryObject = true;
//			od.rowLevelSecurityFilter = "";
			od.fields = fields;
			
			LinkedList<ObjectType> objects = new LinkedList<ObjectType>();
			objects.add(od);			
			
			emd = new ExternalFileSchema();
			emd.fileFormat = fileFormat;
			emd.objects = objects;

		//} catch (Throwable t) {
		//	t.printStackTrace();
		//}
		validateSchema(emd, logger);
		return emd;
	}

	
	public static void save(File schemaFile,ExternalFileSchema emd, PrintStream logger)
	{
		ObjectMapper mapper = new ObjectMapper();	
		try 
		{
			if(!schemaFile.getName().endsWith(SCHEMA_FILE_SUFFIX))
			{
				FilenameUtils.getBaseName(schemaFile.getName());
				schemaFile = new File(schemaFile.getParent(),FilenameUtils.getBaseName(schemaFile.getName())+SCHEMA_FILE_SUFFIX);
			}
			if(DatasetUtilConstants.ext)
			{
				mapper.writerWithDefaultPrettyPrinter().writeValue(schemaFile, emd);
			}else
			{
				mapper.writerWithDefaultPrettyPrinter().writeValue(schemaFile, getBaseSchema(emd,logger));
			}

		} catch (Throwable t) {
			t.printStackTrace(logger);
		}
	}
	
	public static ExternalFileSchema load(File inputCSV, Charset fileCharset, PrintStream logger) throws JsonParseException, JsonMappingException, IOException
	{
		File schemaFile = inputCSV;
		ObjectMapper mapper = new ObjectMapper();	
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		boolean isCSV = false;
		if(!inputCSV.getName().endsWith(SCHEMA_FILE_SUFFIX))
		{
			schemaFile = new File(inputCSV.getParent(),FilenameUtils.getBaseName(inputCSV.getName())+SCHEMA_FILE_SUFFIX);
		}
		ExternalFileSchema userSchema = null;
		if(schemaFile.exists())
		{
			logger.println("Loading existing schema from file {"+ schemaFile +"}");
			InputStreamReader reader = new InputStreamReader(new BOMInputStream(new FileInputStream(schemaFile), false), DatasetUtils.utf8Decoder(null , Charset.forName("UTF-8")));
			userSchema  =  mapper.readValue(reader, ExternalFileSchema.class);			
		}

		if(userSchema==null)
			return null;
		
		if(FilenameUtils.getExtension(inputCSV.getName()).equalsIgnoreCase("csv"))
		{			
			isCSV = true;
		}

			
			String[] header = null;
			if(isCSV)
			{
				CsvListReader reader = null;
				try 
				{
					reader = new CsvListReader(new InputStreamReader(new BOMInputStream(new FileInputStream(inputCSV), false), DatasetUtils.utf8Decoder(null , fileCharset)), CsvPreference.STANDARD_PREFERENCE);
					header = reader.getHeader(true);
				}catch(Throwable t){t.printStackTrace();}
				finally{
					if(reader!=null)
					{
						try {
							reader.close();
							reader = null;
						} catch (Throwable e) {
						}
					}
				}
			}
			
			if(header != null && header.length > 0)
			{
					String devNames[] = ExternalFileSchema.createUniqueDevName(header);
					if(userSchema != null)
					{					
						LinkedList<ObjectType> obj = userSchema.objects;
						if(obj!= null && !obj.isEmpty())
						{
							List<FieldType> fields = obj.get(0).fields;
							if(fields!= null && !fields.isEmpty())
							{
								List<FieldType> fieldsCopy = new LinkedList<FieldType>(fields);
								List<String> fieldNames = new LinkedList<String>();
								for(FieldType field:fieldsCopy)
								{
									if(field.isComputedField)
										continue;
									fieldNames.add(field.getName());
									boolean found = false;
									for (int i=0; i< devNames.length; i++) 
									{
										if(field.getName().equals(devNames[i]))
										{
											found = true;
											break;
										}
									}
									if(!found)
									{
										logger.println("Field {"+field.getName()+"} not found in schema file {"+ schemaFile +"}");
										fields.remove(field);
									}
										
								}
								if(fields.isEmpty())
								{
									throw new IllegalArgumentException("No CSV header Fields "+Arrays.toString(devNames)+" match JSON Fields ["+fieldNames.toString()+"]");
								}
							}
						}
					}
			}
			validateSchema(userSchema, logger);
			return userSchema;
	}
	

	private static void validateSchema(ExternalFileSchema schema, PrintStream logger) throws IllegalArgumentException
	{
		StringBuffer message = new StringBuffer();
		if(schema!=null)
		{
			LinkedList<ObjectType> user_objects = schema.objects;
			if(user_objects!=null && !user_objects.isEmpty())
			{
				int objectCount=0;
				for(ObjectType user_object:user_objects)
				{
						objectCount++;
						List<FieldType> user_fields = user_object.fields;
						if(user_object.name==null||user_object.name.trim().isEmpty())
						{
							message.append("[objects["+objectCount+"].name] in schema cannot be null or empty\n");
						}else
						{
							if(user_object.name.length()>255)
							{
								message.append("object name ["+user_object.name+"] in schema cannot be greater than 255 characters in length\n");
							}else if(!createDevName(user_object.name, "Dataset", (objectCount-1)).equals(user_object.name))
							{
								message.append("Object name {"+user_object.name+"} contains invalid characters \n");
							}
						}

						if(user_object.label==null||user_object.label.trim().isEmpty())
						{
							message.append("[objects["+objectCount+"].label] in schema cannot be null or empty\n");
						}else if(user_object.label.length()>255)
						{
							message.append("object label ["+user_object.label+"] in schema cannot be greater than 255 characters in  length\n");
						}

						if(user_object.fullyQualifiedName==null||user_object.fullyQualifiedName.trim().isEmpty())
						{
							message.append("[objects["+objectCount+"].fullyQualifiedName] in schema cannot be null or empty\n");
						}else if(user_object.fullyQualifiedName.length()>255)
						{
							message.append("object ["+user_object.fullyQualifiedName+"] in schema cannot be greater than 255 characters in  length\n");
						}
						

						if(user_fields!=null && !user_fields.isEmpty())
						{
							HashSet<String> fieldNames = new HashSet<String>();
							HashSet<String> uniqueIdfieldNames = new HashSet<String>();
							int fieldCount=0;
							for(FieldType user_field:user_fields)
							{
								fieldCount++;
								if(user_field != null && user_field.getName()!=null && !user_field.getName().isEmpty())
								{
									if(user_field.getName().length()>255)
									{
										message.append("field name {"+user_field.getName()+"} is greater than 255 characters\n");
									}

									if(!createDevName(user_field.getName(), "Column", (fieldCount-1)).equals(user_field.getName()))
									{
										message.append("field name {"+user_field.getName()+"} contains invalid characters \n");
									}
									
									if(!fieldNames.add(user_field.getName().toUpperCase()))
									{
										message.append("Duplicate field name {"+user_field.getName()+"}\n");
									}
									
									if(user_field.getName().endsWith("_sec_epoch") || user_field.getName().endsWith("_day_epoch") || user_field.getName().endsWith("_Day") || user_field.getName().endsWith("_Month") || user_field.getName().endsWith("_Year") || user_field.getName().endsWith("_Quarter") || user_field.getName().endsWith("_Week"))
									{
										for(FieldType user_field_2:user_fields)
										{
											if(user_field_2 !=null && user_field_2.getType()!=null && user_field_2.getType().equalsIgnoreCase("date") && user_field.getName().contains(user_field_2.getName()))
											{
												message.append("field name {"+user_field.getName()+"} not allowed. When there is field {"+user_field_2.getName()+"} of type Date\n");
											}
										}
									}
								}else
								{
									message.append("[objects["+objectCount+"].fields["+fieldCount+"].name] in schema cannot be null or empty\n");
								}
								
								if(user_field.getLabel()!=null && !user_field.getLabel().trim().isEmpty())
								{
									if(user_field.getLabel().length()>255)
									{
										message.append("field label {"+user_field.getLabel()+"} is greater than 255 characters\n");
									}
								}else
								{
									message.append("[objects["+objectCount+"].fields["+fieldCount+"].label] in schema cannot be null or empty\n");
								}

								if(user_field.getFullyQualifiedName()!=null && !user_field.getFullyQualifiedName().trim().isEmpty())
								{
									if(user_field.getFullyQualifiedName().length()>255)
									{
										message.append("field {"+user_field.getFullyQualifiedName()+"} is greater than 255 characters\n");
									}
								}else
								{
									message.append("[objects["+objectCount+"].fields["+fieldCount+"].fullyQualifiedName] in schema cannot be null or empty\n");
								}
								
								if(user_field.getfType()==FieldType.MEASURE)
								{
									if(user_field.getDefaultValue()==null || !isLatinNumber(user_field.getDefaultValue()))
									{
										message.append("field {"+user_field.getFullyQualifiedName()+"}  in schema must have default numeric value\n");
									}
									
									if(!(user_field.getPrecision()>0 && user_field.getPrecision()<19))
									{
										message.append("field {"+user_field.getFullyQualifiedName()+"}  in schema must have precision between (>0 && <19)\n");
									}
									
									if(user_field.getPrecision()>0 && user_field.getScale()>=user_field.getPrecision())
									{
										message.append("field {"+user_field.getFullyQualifiedName()+"}  in schema must have scale less than the precision\n");
									}
									
								}else if(user_field.getfType()==FieldType.STRING)
								{
									if(user_field.getPrecision()>32000)
									{
										message.append("field {"+user_field.getFullyQualifiedName()+"}  in schema must have precision between (>0 && <32,000)\n");
									}
									
									if(user_field.getPrecision()==0)
									{
										user_field.setPrecision(255);
									}
								}else if(user_field.getfType()==FieldType.DATE)
								{
									if(user_field.getCompiledDateFormat()==null)
									{
										message.append("field {"+user_field.getFullyQualifiedName()+"}  in schema has invalid date format {"+user_field.getFormat()+"}\n");
									}
									
									if(user_field.getFiscalMonthOffset() <0 || user_field.getFiscalMonthOffset() > 11)
									{
										message.append("field {"+user_field.getFullyQualifiedName()+"}  in schema must have FiscalMonthOffset between (0 && 11)\n");
									}

									if(user_field.getFirstDayOfWeek() <-1 || user_field.getFirstDayOfWeek() > 6)
									{
										message.append("field {"+user_field.getFullyQualifiedName()+"}  in schema must have FirstDayOfWeek between (-1 && 6)\n");
									}
								}else
								{
									message.append("field {"+user_field.getFullyQualifiedName()+"}  has invalid type  {"+user_field.getType()+"}\n");
								}
									

								if(user_field.isMultiValue())
								{
									if(user_field.getMultiValueSeparator()==null)
									{
										message.append("field {"+user_field.getFullyQualifiedName()+"}  in schema must have 'multiValueSeparator' value when 'isMultiValue' is 'true'\n");
									}
									
									if(user_field.isUniqueId)
									{
										message.append("MultiValue field {"+user_field.getFullyQualifiedName()+"}  in schema cannot be used as UniqueID\n");
									}
									
									if(user_field.getfType()!=FieldType.STRING)
									{
										message.append("MultiValue field {"+user_field.getFullyQualifiedName()+"}  in schema can only be of Type Text\n");
									}
									
								}
								
								if(user_field.isComputedField)
								{
									if(user_field.getCompiledScript()==null)
									{
										message.append("field {"+user_field.getFullyQualifiedName()+"}  in schema has invalid 'computedFieldExpression' value {"+user_field.getComputedFieldExpression()+"}\n");
									}
								}
								
								if(user_field.isUniqueId)
								{
									uniqueIdfieldNames.add(user_field.getFullyQualifiedName());
								}

							} //End for
							if(uniqueIdfieldNames.size()>1)
							{
								message.append("More than one field has 'isUniqueId' attribute set to true {"+uniqueIdfieldNames+"}\n");
							}
						}else
						{
							message.append("[objects["+objectCount+"].fields] in schema cannot be null or empty\n");
						}
				}
			}else
			{
				message.append("[objects] in schema cannot be null or empty\n");
			}
			if(!hasDim(schema))
			{
				message.append("At least one field in schema should be of Type 'Text'\n");
			}
		}
		if(message.length()!=0)
		{
			throw new IllegalArgumentException(message.toString());
		}
	}

	public static ExternalFileSchema merge(ExternalFileSchema userSchema, ExternalFileSchema autoSchema, PrintStream logger)
	{
		ExternalFileSchema mergedSchema = null;
		try 
		{
			if(userSchema == null)
			{
				return autoSchema;
			}
			LinkedList<ObjectType> user_objects = userSchema.objects;
			LinkedList<ObjectType> auto_objects = autoSchema.objects;
			LinkedList<ObjectType> merged_objects = new LinkedList<ObjectType>();
			if(user_objects==null)
				user_objects = auto_objects;
			for(ObjectType auto_object:auto_objects)
			{
				ObjectType merged_object = null;
				for(ObjectType user_object:user_objects)
				{
					if(auto_object.fullyQualifiedName.equals(user_object.fullyQualifiedName))
					{
						merged_object = new ObjectType();
						List<FieldType> user_fields = user_object.fields;
						List<FieldType> auto_fields = auto_object.fields;
						LinkedList<FieldType> merged_fields = new LinkedList<FieldType>();
						if(user_fields==null || user_fields.isEmpty())
						{
							user_fields = auto_fields;
						}
						if(auto_fields==null || auto_fields.isEmpty())
						{
							auto_fields = user_fields;
						}
						for(FieldType auto_field:auto_fields)
						{
							FieldType merged_field = null;
							boolean found = false;
							for(FieldType user_field:user_fields)
							{
								if(auto_field.getFullyQualifiedName().equals(user_field.getFullyQualifiedName()))
								{
									found = true;
									if(!auto_field.equals(user_field))
									{
										logger.println("Field {"+user_field+"} has been modified by user");
										merged_field = new FieldType(user_field);
										merged_field.setName(user_field.getName()!=null?user_field.getName():auto_field.getName());
										merged_field.setType(user_field.getType()!=null?user_field.getType():auto_field.getType());
										merged_field.setDefaultValue(user_field.getDefaultValue()!=null?user_field.getDefaultValue():auto_field.getDefaultValue());
										merged_field.setDescription(user_field.getDescription()!=null?user_field.getDescription():auto_field.getDescription());
										merged_field.setFiscalMonthOffset(user_field.getFiscalMonthOffset()!=0?user_field.getFiscalMonthOffset():auto_field.getFiscalMonthOffset());
										merged_field.setFormat(user_field.getFormat()!=null?user_field.getFormat():auto_field.getFormat());
										merged_field.setFullyQualifiedName(user_field.getFullyQualifiedName()!=null?user_field.getFullyQualifiedName():auto_field.getFullyQualifiedName());
										merged_field.isMultiValue =  user_field.isMultiValue!=false?user_field.isMultiValue:auto_field.isMultiValue;
										merged_field.isSystemField =  user_field.isSystemField!=false?user_field.isSystemField:auto_field.isSystemField;
										merged_field.isUniqueId =  user_field.isUniqueId!=false?user_field.isUniqueId:auto_field.isUniqueId;
										merged_field.setLabel(user_field.getLabel()!=null?user_field.getLabel():auto_field.getLabel());
										merged_field.setMultiValueSeparator(user_field.getMultiValueSeparator()!=null?user_field.getMultiValueSeparator():auto_field.getMultiValueSeparator());
										merged_field.setPrecision(user_field.getPrecision()!=0?user_field.getPrecision():auto_field.getPrecision());
										merged_field.setScale(user_field.getScale()!=0?user_field.getScale():auto_field.getScale());
									}
								}								
							}
							if(!found)
							{
								logger.println("Found new field {"+auto_field+"} in CSV");
							}
							if(merged_field==null)
							{
								merged_field = auto_field;
							}
							merged_fields.add(merged_field);
						}

						for(FieldType user_field:user_fields)
						{
							if(user_field.isComputedField)
								merged_fields.add(user_field);
						}
						
						merged_object.connector =  user_object.connector!=null?user_object.connector:auto_object.connector;
						merged_object.description =  user_object.description!=null?user_object.description:auto_object.description;
						merged_object.fullyQualifiedName =  user_object.fullyQualifiedName!=null?user_object.fullyQualifiedName:auto_object.fullyQualifiedName;
						merged_object.label =  user_object.label!=null?user_object.label:auto_object.label;
						merged_object.name =  user_object.name!=null?user_object.name:auto_object.name;
//						merged_object.recordTypeIdentifier =  user_object.recordTypeIdentifier!=null?user_object.recordTypeIdentifier:auto_object.recordTypeIdentifier;
						merged_object.rowLevelSecurityFilter =  user_object.rowLevelSecurityFilter!=null?user_object.rowLevelSecurityFilter:auto_object.rowLevelSecurityFilter;
//						merged_object.isPrimaryObject =  user_object.isPrimaryObject!=true?user_object.isPrimaryObject:auto_object.isPrimaryObject;
						merged_object.fields =  merged_fields;						
					}
				}
				if(merged_object==null)
				{
					merged_object = auto_object;

				}
				merged_objects.add(merged_object);
			}
			mergedSchema = new ExternalFileSchema();
			mergedSchema.fileFormat = userSchema.fileFormat!=null?userSchema.fileFormat:autoSchema.fileFormat;
			mergedSchema.objects = merged_objects;
		} catch (Throwable t) {
			t.printStackTrace(logger);
		}
		return mergedSchema;
	}


	public static File getSchemaFile(File csvFile, PrintStream logger) {
		try 
		{
			//init(csvFile);
			if(!csvFile.getName().endsWith(SCHEMA_FILE_SUFFIX))
			{
				FilenameUtils.getBaseName(csvFile.getName());
				csvFile = new File(csvFile.getParent(),FilenameUtils.getBaseName(csvFile.getName())+SCHEMA_FILE_SUFFIX);
				return csvFile;
			}else
			{
				return csvFile;
			}
		} catch (Throwable t) {
			t.printStackTrace(logger);
		}
		return null;
	}
	
	
	public static ExternalFileSchema getSchemaWithNewDateParts(ExternalFileSchema inSchema) 
	{
		if(inSchema == null)
		{
			return inSchema;
		}

		ExternalFileSchema newSchema = new ExternalFileSchema(inSchema);
		try 
		{
			LinkedList<ObjectType> user_objects = newSchema.objects;
			if(user_objects==null || user_objects.isEmpty())
				return newSchema;
			
			for(ObjectType user_object:user_objects)
			{
					List<FieldType> user_fields = user_object.fields;
					LinkedList<FieldType> merged_fields = new LinkedList<FieldType>();
					if(user_fields==null || user_fields.isEmpty())
					{
						return newSchema;
					}
					for(FieldType user_field:user_fields)
					{
						merged_fields.add(user_field);
						if(user_field.getfType() == FieldType.DATE)
						{
							merged_fields.add(FieldType.GetStringKeyDataType(user_field.getName() + "_Hour", null, null));
							merged_fields.add(FieldType.GetStringKeyDataType(user_field.getName() + "_Minute", null, null));
							merged_fields.add(FieldType.GetStringKeyDataType(user_field.getName() + "_Second", null, null));
						
							if(user_field.getFiscalMonthOffset()>0)
							{
								merged_fields.add(FieldType.GetStringKeyDataType(user_field.getName() + "_Month_Fiscal", null, null));
								merged_fields.add(FieldType.GetStringKeyDataType(user_field.getName() + "_Year_Fiscal", null, null));
								merged_fields.add(FieldType.GetStringKeyDataType(user_field.getName() + "_Quarter_Fiscal", null, null));
								merged_fields.add(FieldType.GetStringKeyDataType(user_field.getName() + "_Week_Fiscal", null, null));
							}
						}
					}
					user_object.fields = merged_fields;
				}
		} catch (Throwable t) {
			t.printStackTrace();
		}
		return newSchema;
	}
	
	
	public static String[] createUniqueDevName(String[] headers) 
	{
		if(headers==null)
			return null;
		LinkedList<String> originalColumnNames = new LinkedList<String>();
		LinkedList<String> uniqueColumnNames = new LinkedList<String>();
		LinkedList<String> devNames = new LinkedList<String>();
		for(int i=0;i<headers.length;i++)
		{
			originalColumnNames.add(headers[i]);
			String devName = createDevName(headers[i], "Column", i);
				devNames.add(devName);
		}
			
		for(int i=0;i<headers.length;i++)
		{
			String newName = devNames.get(i);
			if(uniqueColumnNames.contains(newName))
			{
				int index = 1;
				while(true)
				{
					newName = devNames.get(i) + index;
					if(!uniqueColumnNames.contains(newName) && !originalColumnNames.subList(i+1, devNames.size()).contains(newName))
					{
						break;
					}
					index++;
				}
			}else
			{
				//Did we change the column name? if yes check if have a collision with existing columns
				if((headers[i] == null || !newName.equals(headers[i])) && originalColumnNames.subList(i+1, devNames.size()).contains(newName))
				{
					int index = 1;
					while(true)
					{
						newName = devNames.get(i) + index;
						if(!uniqueColumnNames.contains(newName) && !originalColumnNames.subList(i+1, devNames.size()).contains(newName))
						{
							break;
						}
						index++;
					}
				}
			}
			uniqueColumnNames.add(newName);
		}
		return uniqueColumnNames.toArray(new String[0]);
	}
	
	public static String createDevName(String inString, String defaultName, int columnIndex) {
		String outString = inString;
		String suffix = null;
		try 
		{
			if(inString != null && !inString.trim().isEmpty())
			{
				StringBuffer outStr = new StringBuffer(inString.length()+1);
				if(inString.endsWith("__c") && !inString.equals("__c"))
				{
					suffix = "__c";
					inString = inString.substring(0,inString.length()-3);
				}
				@SuppressWarnings("unused")
				int index = 0;	
				boolean hasFirstChar = false;
				boolean lastCharIsUnderscore = false;
				for(char ch:inString.toCharArray())
				{
//					if(Character.isLetterOrDigit((int)ch))
					if(isLatinLetter(ch) || isLatinNumber(ch))
					{
//						if(!hasFirstChar && Character.isDigit((int)ch))
						if(!hasFirstChar && isLatinNumber(ch))
						{
								outStr.append('X');
						}
						outStr.append(ch);
						hasFirstChar = true;
						lastCharIsUnderscore = false;
					}else if(hasFirstChar && !lastCharIsUnderscore)
					{
						outStr.append('_');
						lastCharIsUnderscore = true;
//						if(!(ch == '_'))
//						{
//							lastCharIsUnderscore = true;
//						}
					}
					index++;
				}
			    if (!hasFirstChar) {
			    	outString = defaultName  + (columnIndex+1);
			    } else 
			    {
					outString = outStr.toString();
			    	if(outString.length() > 255) {
			    		outString= outString.substring(0, 255);
			    	}
			    	while(outString.endsWith("_") && outString.length() > 0) {
			    		outString = outString.substring(0, outString.length()-1);
			    	}
			    	if(outString.isEmpty())
			    	{
				    	outString = defaultName  + (columnIndex+1);
			    	}else
			    	{
			    		if(suffix!=null)
			    			outString = outString + suffix;
			    	}
			    }
			}else
			{
				outString = defaultName  + (columnIndex+1);
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
		return outString;
	}
	
	@SuppressWarnings("unused")
	private static String replaceString(String original, String pattern, String replace) 
	{
		if(original != null && !original.isEmpty() && pattern != null && !pattern.isEmpty() && replace !=null)
		{
			final int len = pattern.length();
			int found = original.indexOf(pattern);

			if (found > -1) {
				StringBuffer sb = new StringBuffer();
				int start = 0;

				while (found != -1) {
					sb.append(original.substring(start, found));
					sb.append(replace);
					start = found + len;
					found = original.indexOf(pattern, start);
				}

				sb.append(original.substring(start));

				return sb.toString();
			} else {
				return original;
			}
		}else
			return original;
	}
	
	public static boolean isLatinLetter(char c) {
	    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
	}
	
	public static boolean isLatinNumber(char c) {
	    return (c >= '0' && c <= '9');
	}

	public static boolean isLatinNumber(String str) {
		if (str == null) {
			return false;
		}
		int sz = str.length();
		if (sz == 0) {
			return false;
		}

		for (int i = 0; i < sz; i++) {
			char c = str.charAt(i);
			if (!(c >= '0' && c <= '9'))
				return false;
		}
		return true;
	}
	
	@Override
	public String toString()
	{
		try 
		{  
			ObjectMapper mapper = new ObjectMapper();	
			if(DatasetUtilConstants.ext)
			{
				return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
			}else
			{
				return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(getBaseSchema(this,System.out));
			}
		} catch (Throwable t) 
		{
				t.printStackTrace();
				return super.toString();
		}
	}

	public static com.sforce.dataset.loader.file.schema.ExternalFileSchema getBaseSchema(ExternalFileSchema emd, PrintStream logger)
	{
		try 
		{
				ObjectMapper mapper = new ObjectMapper();
				mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
				String temp =  mapper.writerWithDefaultPrettyPrinter().writeValueAsString(emd);
				return mapper.readValue(temp, com.sforce.dataset.loader.file.schema.ExternalFileSchema.class);
		} catch (Throwable t) 
		{
				t.printStackTrace(logger);
		}
		return null;
	}


	public byte[] toBytes()
	{
		try 
		{
				ObjectMapper mapper = new ObjectMapper();	
				if(DatasetUtilConstants.ext)
				{
					return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this).getBytes(Charset.forName("UTF-8"));
				}else
				{
					return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(getBaseSchema(this,System.out)).getBytes(Charset.forName("UTF-8"));
				}
		} catch (Throwable t) 
		{
				t.printStackTrace();
				return super.toString().getBytes();
		}
		
	}
	

	public static ExternalFileSchema load(InputStream inputStream, Charset fileCharset, PrintStream logger) throws JsonParseException, JsonMappingException, IOException
	{
		ObjectMapper mapper = new ObjectMapper();	
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		InputStreamReader reader = new InputStreamReader(new BOMInputStream(inputStream, false), DatasetUtils.utf8Decoder(null , Charset.forName("UTF-8")));
		ExternalFileSchema userSchema = mapper.readValue(reader, ExternalFileSchema.class);
		if(userSchema==null)
		{
			throw new IllegalArgumentException("Could not read schema from stream {null}");
		}
		validateSchema(userSchema, logger);
		return userSchema;
	}
	
	
	public static HashSet<String> getUniqueId(ExternalFileSchema inSchema) 
	{
		HashSet<String> uniqueIdfieldNames = new HashSet<String>();
		if(inSchema!= null && inSchema.objects != null && inSchema.objects.size() > 0 && inSchema.objects.get(0).fields != null)
		{
			for(FieldType user_field: inSchema.objects.get(0).fields)
			{
				if(user_field != null && user_field.isUniqueId)
					uniqueIdfieldNames.add(user_field.getFullyQualifiedName());
			}
		}
		return uniqueIdfieldNames;
	}

	public static boolean hasUniqueID(ExternalFileSchema inSchema) 
	{
		boolean hasUniqueID = false;
		if(inSchema!= null && inSchema.objects != null && inSchema.objects.size() > 0 && inSchema.objects.get(0).fields != null)
		{
			for(FieldType user_field: inSchema.objects.get(0).fields)
			{
				if(user_field != null && user_field.isUniqueId)
					hasUniqueID = true;
			}
		}
		return hasUniqueID;
	}

	public static boolean hasDim(ExternalFileSchema inSchema) 
	{
		boolean hasDim = false;
		if(inSchema!= null && inSchema.objects != null && inSchema.objects.size() > 0 && inSchema.objects.get(0).fields != null)
		{
			for(FieldType user_field: inSchema.objects.get(0).fields)
			{
				if(user_field != null && user_field.getfType()==FieldType.STRING)
					hasDim = true;
			}
		}
		return hasDim;
	}


	public static void setUniqueId(ExternalFileSchema inSchema, HashSet<String> uniqueIdfieldNames) 
	{
		if(inSchema!= null && inSchema.objects != null && inSchema.objects.size() > 0 && inSchema.objects.get(0).fields != null && uniqueIdfieldNames != null)
		{
			for(FieldType user_field: inSchema.objects.get(0).fields)
			{
				if(uniqueIdfieldNames.contains(user_field.getFullyQualifiedName()))
						user_field.isUniqueId = true;
			}
		}
	}


	
}