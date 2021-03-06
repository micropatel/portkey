/**
 * 
 */
package com.flipkart.portkey.redis.metadata;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.flipkart.portkey.common.entity.Entity;
import com.flipkart.portkey.common.exception.InvalidAnnotationException;
import com.flipkart.portkey.common.metadata.MetaDataCache;
import com.flipkart.portkey.redis.metadata.annotation.RedisDataStore;
import com.flipkart.portkey.redis.metadata.annotation.RedisField;

/**
 * @author santosh.p
 */
public class RedisMetaDataCache implements MetaDataCache
{
	private Map<Class<? extends Entity>, RedisMetaData> entityToMetaDataMap =
	        new HashMap<Class<? extends Entity>, RedisMetaData>();
	private static RedisMetaDataCache instance = null;

	public static RedisMetaDataCache getInstance()
	{
		if (instance == null)
		{
			instance = new RedisMetaDataCache();
		}
		return instance;
	}

	public RedisMetaData getMetaData(Class<? extends Entity> clazz) throws InvalidAnnotationException
	{
		RedisMetaData metaData = entityToMetaDataMap.get(clazz);
		if (metaData == null)
		{
			addMetaDataToCache(clazz);
			return entityToMetaDataMap.get(clazz);
		}
		return metaData;
	}

	/**
	 * @param keyword
	 * @param bean
	 * @param metaData
	 * @return
	 * @throws InvalidAnnotationException
	 */
	private <T extends Entity> String getReplacementValueForKeyWord(String keyword, Class<T> clazz)
	        throws InvalidAnnotationException
	{
		if (keyword.equalsIgnoreCase("CLASS"))
		{
			return clazz.getSimpleName();
		}
		throw new InvalidAnnotationException("Exception while trying to parse primary key, invalid keyword:" + keyword);
	}

	private <T extends Entity> String parseKeyPattern(String keyPattern, Class<T> clazz)
	        throws InvalidAnnotationException
	{
		String key;
		key = keyPattern;
		Matcher keywordMatcher = Pattern.compile("\\[(.*?)\\]").matcher(key);
		List<String> keywords = new ArrayList<String>();
		while (keywordMatcher.find())
		{
			keywords.add(keywordMatcher.group(1));
		}
		for (String keyword : keywords)
		{
			String replacementValue = getReplacementValueForKeyWord(keyword, clazz);
			key = key.replace("[" + keyword + "]", replacementValue);
		}
		return key;
	}

	List<String> getAttributesFromKeyPattern(String keyPattern)
	{
		Matcher attributeMatcher = Pattern.compile("\\{(.*?)\\}").matcher(keyPattern);
		List<String> attributes = new ArrayList<String>();
		while (attributeMatcher.find())
		{
			attributes.add(attributeMatcher.group(1));
		}
		return attributes;
	}

	/**
	 * @param clazz
	 * @param bean
	 * @throws InvalidAnnotationException
	 */
	private <T extends Entity> void addMetaDataToCache(Class<T> clazz) throws InvalidAnnotationException
	{
		// metadata specific to redis
		RedisMetaData redisMetaData = new RedisMetaData();

		RedisDataStore redisDataStore = clazz.getAnnotation(RedisDataStore.class);
		redisMetaData.setDatabase(redisDataStore.database());
		String primaryKeyPattern = redisDataStore.primaryKeyPattern();
		String parsedPrimaryKeyPattern = parseKeyPattern(primaryKeyPattern, clazz);
		redisMetaData.setPrimaryKeyPattern(parsedPrimaryKeyPattern);
		List<String> primaryKeyAttributes = getAttributesFromKeyPattern(primaryKeyPattern);
		redisMetaData.setPrimaryKeyAttributes(primaryKeyAttributes);

		List<String> secondaryKeyPatterns = Arrays.asList(redisDataStore.secondaryKeyPatterns());
		for (String secondaryKeyPattern : secondaryKeyPatterns)
		{
			String parsedPattern = parseKeyPattern(secondaryKeyPattern, clazz);
			redisMetaData.addToSecondaryKeyPatterns(parsedPattern);
			List<String> secondaryKeyAttributes = getAttributesFromKeyPattern(secondaryKeyPattern);
			redisMetaData.addToSecondaryKeyAttributesList(secondaryKeyAttributes);
			redisMetaData.addToSecondaryKeyPatternToAttributeList(parsedPattern, secondaryKeyAttributes);
		}

		Field[] fields = clazz.getDeclaredFields();
		for (Field field : fields)
		{
			RedisField redisField = field.getAnnotation(RedisField.class);
			if (redisField != null)
			{
				String fieldName = field.getName();
				redisMetaData.addToFieldNameToFieldMap(fieldName, field);
				redisMetaData.addToFields(field);
			}
		}

		String shardKeyField = redisDataStore.shardKeyField();
		redisMetaData.setShardKey(shardKeyField);
		entityToMetaDataMap.put(clazz, redisMetaData);
	}

	/*
	 * (non-Javadoc)
	 * @see com.flipkart.portkey.common.metadata.MetaDataCache#getShardKey(java.lang.Class)
	 */
	public <T extends Entity> String getShardKeyFieldName(Class<T> clazz) throws InvalidAnnotationException
	{
		return getMetaData(clazz).getShardKey();
	}
}
