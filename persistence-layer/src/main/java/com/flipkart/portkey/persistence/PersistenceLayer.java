/**
 * 
 */
package com.flipkart.portkey.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;

import com.flipkart.portkey.common.datastore.DataStoreConfig;
import com.flipkart.portkey.common.entity.Entity;
import com.flipkart.portkey.common.entity.persistence.EntityPersistencePreference;
import com.flipkart.portkey.common.entity.persistence.ReadConfig;
import com.flipkart.portkey.common.entity.persistence.WriteConfig;
import com.flipkart.portkey.common.enumeration.DataStoreType;
import com.flipkart.portkey.common.enumeration.FailureAction;
import com.flipkart.portkey.common.enumeration.ShardStatus;
import com.flipkart.portkey.common.exception.QueryExecutionException;
import com.flipkart.portkey.common.exception.ShardNotAvailableException;
import com.flipkart.portkey.common.metadata.MetaDataCache;
import com.flipkart.portkey.common.persistence.PersistenceLayerInterface;
import com.flipkart.portkey.common.persistence.PersistenceManager;
import com.flipkart.portkey.common.persistence.Result;
import com.flipkart.portkey.common.sharding.ShardIdentifier;
import com.flipkart.portkey.common.sharding.ShardLifeCycleManagerInterface;
import com.flipkart.portkey.common.util.PortKeyUtils;
import com.flipkart.portkey.sharding.SimpleShardLifeCycleManager;

/**
 * @author santosh.p
 */
public class PersistenceLayer implements PersistenceLayerInterface, InitializingBean
{
	private static final Logger logger = Logger.getLogger(PersistenceLayer.class);
	private EntityPersistencePreference defaultPersistencePreference;
	private Map<Class<? extends Entity>, EntityPersistencePreference> entityPersistencePreferenceMap;
	private Map<DataStoreType, DataStoreConfig> dataStoreConfigMap;
	private ShardLifeCycleManagerInterface shardLifeCycleManager;
	private ScheduledExecutorService scheduledThreadPool;
	private Map<DataStoreType, Map<String, ShardStatus>> shardStatusMap;

	class HealthCheckScheduler implements Runnable
	{
		@Override
		public void run()
		{
			logger.info("Running healthckecker");
			// TODO: try fetching a snapshot of shard statuses, instead of individual fetches from shardLifeCycleManager
			for (DataStoreType type : dataStoreConfigMap.keySet())
			{
				DataStoreConfig ds = dataStoreConfigMap.get(type);
				List<String> shardIds = ds.getShardIds();
				for (String shardId : shardIds)
				{
					PersistenceManager pm = ds.getPersistenceManager(shardId);
					ShardStatus currentStatus = pm.healthCheck();
					ShardStatus previousStatus = shardLifeCycleManager.getShardStatus(type, shardId);
					logger.info("datastoretype=" + type + " shardId=" + shardId + " current status=" + currentStatus
					        + " previous status=" + previousStatus);
					if (!previousStatus.equals(currentStatus))
					{
						shardLifeCycleManager.setShardStatus(type, shardId, currentStatus);
						shardStatusMap.get(type).put(shardId, currentStatus);
					}
				}
			}
			logger.info("Health check complete");
		}
	}

	public void setDefaultPersistencePreference(EntityPersistencePreference defaultPersistencePreference)
	{
		System.out.println("Setting default persistence preference");
		this.defaultPersistencePreference = defaultPersistencePreference;
	}

	public void setEntityPersistencePreferenceMap(
	        Map<Class<? extends Entity>, EntityPersistencePreference> entityPersistencePreferenceMap)
	{
		System.out.println("Setting entity persistence preference map");
		this.entityPersistencePreferenceMap = entityPersistencePreferenceMap;
	}

	public void setDataStoresMap(Map<DataStoreType, DataStoreConfig> dataStoresMap)
	{
		System.out.println("Setting data store config map");
		this.dataStoreConfigMap = dataStoresMap;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Assert.notNull(defaultPersistencePreference);
		Assert.notNull(dataStoreConfigMap);

		logger.info("Assertions passed");

		logger.info("Initializing Shard Life Cycle Manager");
		List<DataStoreType> dataStoreTypes = new ArrayList<DataStoreType>(dataStoreConfigMap.keySet());
		shardStatusMap = new HashMap<DataStoreType, Map<String, ShardStatus>>();
		for (DataStoreType type : dataStoreTypes)
		{
			shardStatusMap.put(type, new HashMap<String, ShardStatus>());
		}
		shardLifeCycleManager = new SimpleShardLifeCycleManager(dataStoreTypes);
		logger.info("number of data stores to be registered=" + dataStoreTypes.size());
		logger.info("datastores=" + dataStoreTypes);
		for (DataStoreType type : dataStoreTypes)
		{
			DataStoreConfig ds = dataStoreConfigMap.get(type);
			List<String> shardIds = ds.getShardIds();
			logger.info("registering " + shardIds.size() + " shards for datastoretype=" + dataStoreTypes);
			for (String shardId : shardIds)
			{
				logger.info("registering shard id=" + shardId);
				shardLifeCycleManager.setShardStatus(type, shardId, ShardStatus.getDefaultStatus());
			}
		}
		logger.info("ShardLifeCycleManager initialized");
		scheduledThreadPool = Executors.newScheduledThreadPool(5);
		HealthCheckScheduler healthCheckScheduler = new HealthCheckScheduler();
		healthCheckScheduler.run();
		scheduledThreadPool.scheduleAtFixedRate(healthCheckScheduler, 0, 15, TimeUnit.SECONDS);
		logger.info("scheduled health checker");
		logger.info("Initialized Persistence Layer");
	}

	public void shutdown()
	{
		scheduledThreadPool.shutdown();
	}

	private EntityPersistencePreference getDefaultPersistencePreference()
	{
		return defaultPersistencePreference;
	}

	private ReadConfig getDefaultReadConfig()
	{
		EntityPersistencePreference defaultPersistencePreference = getDefaultPersistencePreference();
		return defaultPersistencePreference.getReadConfig();
	}

	private WriteConfig getDefaultWriteConfig()
	{
		EntityPersistencePreference defaultPersistencePreference = getDefaultPersistencePreference();
		return defaultPersistencePreference.getWriteConfig();
	}

	private EntityPersistencePreference getEntityPersistencePreference(Class<? extends Entity> entity)
	{
		if (entityPersistencePreferenceMap == null)
		{
			return getDefaultPersistencePreference();
		}
		return entityPersistencePreferenceMap.get(entity);
	}

	private WriteConfig getWriteConfigForEntity(Class<? extends Entity> clazz)
	{
		EntityPersistencePreference entityPersistencePreference = getEntityPersistencePreference(clazz);
		if (entityPersistencePreference == null)
		{
			return getDefaultWriteConfig();
		}
		return entityPersistencePreference.getWriteConfig();
	}

	private ReadConfig getReadConfigForEntity(Class<? extends Entity> entity)
	{
		EntityPersistencePreference entityPersistencePreference = getEntityPersistencePreference(entity);
		if (entityPersistencePreference == null)
		{
			return getDefaultReadConfig();
		}
		return entityPersistencePreference.getReadConfig();
	}

	private DataStoreConfig getDataStoreConfig(DataStoreType type)
	{
		return dataStoreConfigMap.get(type);
	}

	private PersistenceManager getPersistenceManager(DataStoreType type, String shardId)
	{
		DataStoreConfig ds = getDataStoreConfig(type);
		return ds.getPersistenceManager(shardId);
	}

	private MetaDataCache getMetaDataCache(DataStoreType type)
	{
		DataStoreConfig dataStore = getDataStoreConfig(type);
		return dataStore.getMetaDataCache();
	}

	private ShardIdentifier getShardIdentifier(DataStoreType type)
	{
		DataStoreConfig dataStore = getDataStoreConfig(type);
		return dataStore.getShardIdentifier();
	}

	private <T extends Entity> int insertIntoDataStore(DataStoreType type, T bean) throws QueryExecutionException
	{
		MetaDataCache metaDataCache = getMetaDataCache(type);
		String shardKeyFieldName = metaDataCache.getShardKey(bean.getClass());
		String shardKey = PortKeyUtils.toString(PortKeyUtils.getFieldValueFromBean(bean, shardKeyFieldName));
		ShardIdentifier shardIdentifier = getShardIdentifier(type);
		List<String> liveShards = shardLifeCycleManager.getShardListForStatus(type, ShardStatus.AVAILABLE_FOR_WRITE);
		String shardId = shardIdentifier.getShardId(shardKey, liveShards);
		PersistenceManager pm = getPersistenceManager(type, shardId);
		int rowsUpdated = pm.insert(bean);
		return rowsUpdated;
	}

	private <T extends Entity> int upsertIntoDataStore(DataStoreType type, T bean,
	        List<String> columnsToBeUpdatedOnDuplicate) throws QueryExecutionException
	{
		MetaDataCache metaDataCache = getMetaDataCache(type);
		String shardKeyFieldName = metaDataCache.getShardKey(bean.getClass());
		String shardKey = PortKeyUtils.toString(PortKeyUtils.getFieldValueFromBean(bean, shardKeyFieldName));
		ShardIdentifier shardIdentifier = getShardIdentifier(type);
		List<String> liveShards = shardLifeCycleManager.getShardListForStatus(type, ShardStatus.AVAILABLE_FOR_WRITE);
		String shardId = shardIdentifier.getShardId(shardKey, liveShards);
		PersistenceManager pm = getPersistenceManager(type, shardId);
		int rowsUpdated = pm.upsert(bean, columnsToBeUpdatedOnDuplicate);
		return rowsUpdated;
	}

	private <T extends Entity> int upsertIntoDataStore(DataStoreType type, T bean) throws QueryExecutionException
	{
		MetaDataCache metaDataCache = getMetaDataCache(type);
		String shardKeyFieldName = metaDataCache.getShardKey(bean.getClass());
		String shardKey = PortKeyUtils.toString(PortKeyUtils.getFieldValueFromBean(bean, shardKeyFieldName));
		ShardIdentifier shardIdentifier = getShardIdentifier(type);
		List<String> liveShards = shardLifeCycleManager.getShardListForStatus(type, ShardStatus.AVAILABLE_FOR_WRITE);
		String shardId = shardIdentifier.getShardId(shardKey, liveShards);
		PersistenceManager pm = getPersistenceManager(type, shardId);
		int rowsUpdated = pm.upsert(bean);
		return rowsUpdated;
	}

	private <T extends Entity> String getShardKey(DataStoreType type, T bean)
	{
		MetaDataCache metaDataCache = getMetaDataCache(type);
		String shardKeyFieldName = metaDataCache.getShardKey(bean.getClass());
		String shardKey;
		shardKey = PortKeyUtils.toString(PortKeyUtils.getFieldValueFromBean(bean, shardKeyFieldName));
		return shardKey;
	}

	private ShardLifeCycleManagerInterface getShardLifeCycleManager()
	{
		return this.shardLifeCycleManager;
	}

	private <T extends Entity> T generateShardIdAndUpdateBean(DataStoreType type, T bean)
	        throws ShardNotAvailableException
	{
		MetaDataCache metaDataCache = getMetaDataCache(type);
		ShardIdentifier shardIdentifier = getShardIdentifier(type);
		String shardKeyFieldName = metaDataCache.getShardKey(bean.getClass());
		String shardKey = PortKeyUtils.toString(PortKeyUtils.getFieldValueFromBean(bean, shardKeyFieldName));
		ShardLifeCycleManagerInterface shardLifeCycleManager = getShardLifeCycleManager();
		List<String> liveShards = shardLifeCycleManager.getShardListForStatus(type, ShardStatus.AVAILABLE_FOR_WRITE);
		String shardId = shardIdentifier.generateShardId(shardKey, liveShards);
		String newShardKey = shardIdentifier.generateNewShardKey(shardKey, shardId);
		PortKeyUtils.setFieldValueInBean(bean, shardKeyFieldName, newShardKey);
		return bean;
	}

	public <T extends Entity> Result insert(T bean) throws QueryExecutionException
	{
		return insert(bean, false);
	}

	public <T extends Entity> Result insert(T bean, boolean generateShardId) throws QueryExecutionException
	{
		Result result = new Result();
		WriteConfig writeConfig = getWriteConfigForEntity(bean.getClass());
		List<DataStoreType> writeOrder = writeConfig.getWriteOrder();
		if (generateShardId)
		{
			DataStoreType dataStoreTypeForShardIdGeneration = writeOrder.get(0);
			bean = generateShardIdAndUpdateBean(dataStoreTypeForShardIdGeneration, bean);
		}

		for (DataStoreType dataStoreType : writeOrder)
		{
			int rowsUpdated = 0;
			try
			{
				rowsUpdated = insertIntoDataStore(dataStoreType, bean);
			}
			catch (QueryExecutionException e)
			{
				logger.info("Caught exception while trying to insert bean=" + bean + "\n into data store"
				        + dataStoreType + "\n", e);
				FailureAction failureAction = writeConfig.getFailureAction();
				if (failureAction == FailureAction.ABORT)
				{
					throw new QueryExecutionException("Exception while inserting bean into datastore, bean=" + bean
					        + "\ndatastoretype=" + dataStoreType, e);
				}
			}
			result.setRowsUpdatedForDataStore(dataStoreType, rowsUpdated);
		}
		result.setEntity(bean);
		return result;
	}

	public <T extends Entity> Result upsert(T bean) throws QueryExecutionException
	{
		Result result = new Result();
		WriteConfig writeConfig = getWriteConfigForEntity(bean.getClass());
		List<DataStoreType> writeOrder = writeConfig.getWriteOrder();

		for (DataStoreType dataStoreType : writeOrder)
		{
			int rowsUpdated = 0;
			try
			{
				rowsUpdated = upsertIntoDataStore(dataStoreType, bean);
			}
			catch (QueryExecutionException e)
			{
				logger.info("Caught exception while trying to insert bean=" + bean + "\n into data store"
				        + dataStoreType + "\n", e);
				FailureAction failureAction = writeConfig.getFailureAction();
				if (failureAction == FailureAction.ABORT)
				{
					throw new QueryExecutionException("Exception while inserting bean into datastore, bean=" + bean
					        + "\ndatastoretype=" + dataStoreType, e);
				}
			}
			result.setRowsUpdatedForDataStore(dataStoreType, rowsUpdated);
		}
		result.setEntity(bean);
		return result;
	}

	@Override
	public <T extends Entity> Result upsert(T bean, List<String> columnsToBeUpdatedOnDuplicate)
	        throws QueryExecutionException
	{
		Result result = new Result();
		WriteConfig writeConfig = getWriteConfigForEntity(bean.getClass());
		List<DataStoreType> writeOrder = writeConfig.getWriteOrder();

		for (DataStoreType dataStoreType : writeOrder)
		{
			int rowsUpdated = 0;
			try
			{
				rowsUpdated = upsertIntoDataStore(dataStoreType, bean, columnsToBeUpdatedOnDuplicate);
			}
			catch (QueryExecutionException e)
			{
				logger.info("Caught exception while trying to insert bean=" + bean + "\n into data store"
				        + dataStoreType + "\n", e);
				FailureAction failureAction = writeConfig.getFailureAction();
				if (failureAction == FailureAction.ABORT)
				{
					throw new QueryExecutionException("Exception while inserting bean into datastore, bean=" + bean
					        + "\ndatastoretype=" + dataStoreType, e);
				}
			}
			result.setRowsUpdatedForDataStore(dataStoreType, rowsUpdated);
		}
		result.setEntity(bean);
		return result;
	}

	public <T extends Entity> Result update(T bean) throws QueryExecutionException
	{
		Result result = new Result();
		WriteConfig writeConfig = getWriteConfigForEntity(bean.getClass());
		List<DataStoreType> writeOrder = writeConfig.getWriteOrder();
		for (DataStoreType type : writeOrder)
		{
			String shardKey = getShardKey(type, bean);
			ShardIdentifier shardIdentifier = getShardIdentifier(type);
			List<String> liveShards =
			        shardLifeCycleManager.getShardListForStatus(type, ShardStatus.AVAILABLE_FOR_WRITE);
			int rowsUpdated = 0;
			try
			{
				String shardId = shardIdentifier.getShardId(shardKey, liveShards);
				PersistenceManager pm = getPersistenceManager(type, shardId);
				rowsUpdated = pm.update(bean);
			}
			catch (QueryExecutionException e)
			{
				logger.info("Caught exception while trying to update bean=" + bean + "\n into data store" + type + "\n"
				        + e);
				FailureAction failureAction = writeConfig.getFailureAction();
				if (failureAction == FailureAction.ABORT)
				{
					throw new QueryExecutionException("Exception while updating bean, bean=" + bean
					        + "\ndatastoretype=" + type, e);
				}
			}
			result.setRowsUpdatedForDataStore(type, rowsUpdated);
		}
		result.setEntity(bean);
		return result;
	}

	public <T extends Entity> Result update(Class<T> clazz, Map<String, Object> updateValuesMap,
	        Map<String, Object> criteria) throws QueryExecutionException
	{
		Result result = new Result();
		WriteConfig writeConfig = getWriteConfigForEntity(clazz);
		List<DataStoreType> writeOrder = writeConfig.getWriteOrder();
		for (DataStoreType type : writeOrder)
		{
			MetaDataCache metaDataCache = getMetaDataCache(type);
			String shardKeyFieldName = metaDataCache.getShardKey(clazz);
			if (criteria.containsKey(shardKeyFieldName))
			{
				String shardKey = PortKeyUtils.toString(criteria.get(shardKeyFieldName));
				ShardIdentifier shardIdentifier = getShardIdentifier(type);
				List<String> liveShards =
				        shardLifeCycleManager.getShardListForStatus(type, ShardStatus.AVAILABLE_FOR_WRITE);
				int rowsUpdated = 0;
				try
				{
					String shardId = shardIdentifier.getShardId(shardKey, liveShards);
					PersistenceManager pm = getPersistenceManager(type, shardId);
					rowsUpdated = pm.update(clazz, updateValuesMap, criteria);
				}
				catch (QueryExecutionException e)
				{
					logger.info("Caught exception while trying to execute update " + updateValuesMap + "\n data store:"
					        + type + "\n" + e);
					FailureAction failureAction = writeConfig.getFailureAction();
					if (failureAction == FailureAction.ABORT)
					{
						throw new QueryExecutionException("Exception while trying to execute update " + updateValuesMap
						        + "\n data store:" + type, e);
					}
				}
				result.setRowsUpdatedForDataStore(type, rowsUpdated);
			}
			else
			{
				DataStoreConfig dataStoreConfig = getDataStoreConfig(type);
				List<String> shardIds = dataStoreConfig.getShardIds();
				int rowsUpdated = 0;
				boolean updated = false;
				for (String shardId : shardIds)
				{
					PersistenceManager pm = getPersistenceManager(type, shardId);
					try
					{
						rowsUpdated += pm.update(clazz, updateValuesMap, criteria);
						updated = true;
					}
					catch (QueryExecutionException e)
					{
						break;
					}
				}
				if (!updated)
				{
					result.setRowsUpdatedForDataStore(type, rowsUpdated);
					continue;
				}
				result.setRowsUpdatedForDataStore(type, rowsUpdated);
			}
		}
		return result;
	}

	public <T extends Entity> Result delete(Class<T> clazz, Map<String, Object> criteria)
	        throws QueryExecutionException
	{
		Result result = new Result();
		WriteConfig writeConfig = getWriteConfigForEntity(clazz);
		List<DataStoreType> writeOrder = writeConfig.getWriteOrder();
		for (DataStoreType type : writeOrder)
		{
			MetaDataCache metaDataCache = getMetaDataCache(type);
			String shardKeyFieldName = metaDataCache.getShardKey(clazz);
			if (criteria.containsKey(shardKeyFieldName))
			{
				int rowsUpdated = 0;
				String shardKey = (String) criteria.get(shardKeyFieldName);
				ShardIdentifier shardIdentifier = getShardIdentifier(type);
				List<String> liveShards =
				        shardLifeCycleManager.getShardListForStatus(type, ShardStatus.AVAILABLE_FOR_WRITE);
				try
				{
					String shardId = shardIdentifier.getShardId(shardKey, liveShards);
					PersistenceManager pm = getPersistenceManager(type, shardId);
					rowsUpdated = pm.delete(clazz, criteria);
					result.setRowsUpdatedForDataStore(type, rowsUpdated);
				}
				catch (QueryExecutionException e)
				{
					logger.info("Caught exception while trying to delete from data store" + type + "\n" + e);
					FailureAction failureAction = writeConfig.getFailureAction();
					if (failureAction == FailureAction.ABORT)
					{
						throw new QueryExecutionException("Exception while executing delete from datastore, type="
						        + type, e);
					}
					result.setRowsUpdatedForDataStore(type, rowsUpdated);
					continue;
				}
			}
			else
			{
				List<String> shardIds = dataStoreConfigMap.get(type).getShardIds();
				int rowsUpdated = 0;
				for (String shardId : shardIds)
				{
					PersistenceManager pm = getPersistenceManager(type, shardId);
					rowsUpdated += pm.delete(clazz, criteria);
				}
				result.setRowsUpdatedForDataStore(type, rowsUpdated);
			}
		}
		return result;
	}

	public <T extends Entity> List<T> getByCriteria(Class<T> clazz, Map<String, Object> criteria)
	        throws QueryExecutionException
	{
		List<T> result = new ArrayList<T>();
		ReadConfig readConfig = getReadConfigForEntity(clazz);
		List<DataStoreType> readOrder = readConfig.getReadOrder();
		for (DataStoreType type : readOrder)
		{
			MetaDataCache metaDataCache = getMetaDataCache(type);
			String shardKeyFieldName = metaDataCache.getShardKey(clazz);
			if (criteria.containsKey(shardKeyFieldName))
			{
				String shardKey = (String) criteria.get(shardKeyFieldName);
				ShardIdentifier shardIdentifier = getShardIdentifier(type);
				List<String> liveShards =
				        shardLifeCycleManager.getShardListForStatus(type, ShardStatus.AVAILABLE_FOR_WRITE);
				try
				{
					String shardId = shardIdentifier.getShardId(shardKey, liveShards);
					PersistenceManager pm = getPersistenceManager(type, shardId);
					result = pm.getByCriteria(clazz, criteria);
				}
				catch (QueryExecutionException e)
				{
					logger.info("Encountered exception while trying to execute query \n DataStoreType=" + type
					        + "\ncriteria=" + criteria + "\nexception=" + e);
					continue;
				}
				return result;
			}
			else
			{
				boolean queryExecuted = false;
				List<String> shardIds = dataStoreConfigMap.get(type).getShardIds();
				for (String shardId : shardIds)
				{
					PersistenceManager pm = getPersistenceManager(type, shardId);
					List<T> intermediateResult;
					try
					{
						intermediateResult = pm.getByCriteria(clazz, criteria);
						queryExecuted = true;
					}
					catch (QueryExecutionException e)
					{
						logger.info("Encountered exception while trying to execute query \n DataStoreType=" + type
						        + "\ncriteria=" + criteria + "\nexception=" + e);
						break;
					}
					result.addAll(intermediateResult);
				}
				if (!queryExecuted)
				{
					continue;
				}
				return result;
			}
		}
		throw new QueryExecutionException("Failed to execute query.");
	}

	public <T extends Entity> List<T> getByCriteria(Class<T> clazz, List<String> attributeNames,
	        Map<String, Object> criteria) throws QueryExecutionException
	{
		List<T> result = new ArrayList<T>();
		ReadConfig readConfig = getReadConfigForEntity(clazz);
		List<DataStoreType> readOrder = readConfig.getReadOrder();
		for (DataStoreType type : readOrder)
		{
			boolean queryExecuted = false;
			MetaDataCache metaDataCache = getMetaDataCache(type);
			String shardKeyFieldName = metaDataCache.getShardKey(clazz);
			if (criteria.containsKey(shardKeyFieldName))
			{
				String shardKey = (String) criteria.get(shardKeyFieldName);
				ShardIdentifier shardIdentifier = getShardIdentifier(type);
				List<String> liveShards =
				        shardLifeCycleManager.getShardListForStatus(type, ShardStatus.AVAILABLE_FOR_READ);
				String shardId;
				try
				{
					shardId = shardIdentifier.getShardId(shardKey, liveShards);
					PersistenceManager pm = getPersistenceManager(type, shardId);
					result = pm.getByCriteria(clazz, attributeNames, criteria);
					queryExecuted = true;
				}
				catch (QueryExecutionException e)
				{
					logger.info("Exception while trying to fetch from data store:" + type, e);
					continue;
				}
			}
			else
			{
				List<String> shardIds = dataStoreConfigMap.get(type).getShardIds();
				for (String shardId : shardIds)
				{
					queryExecuted = false;
					PersistenceManager pm = getPersistenceManager(type, shardId);
					List<T> intermediateResult = null;
					try
					{
						intermediateResult = pm.getByCriteria(clazz, attributeNames, criteria);
						result.addAll(intermediateResult);
						queryExecuted = true;
					}
					catch (QueryExecutionException e)
					{
						logger.info("Exception while trying to fetch from data store:" + type, e);
						break;
					}
				}
			}
			if (queryExecuted)
			{
				return result;
			}
		}
		throw new QueryExecutionException("Failed to execute query.");
	}

	public <T extends Entity> List<T> getBySql(Class<T> clazz, String sql, Map<String, Object> criteria)
	        throws QueryExecutionException
	{
		ReadConfig readConfig = getReadConfigForEntity(clazz);
		List<DataStoreType> readOrder = readConfig.getReadOrder();
		for (DataStoreType type : readOrder)
		{
			try
			{
				List<T> result = new ArrayList<T>();
				List<String> shardIds = dataStoreConfigMap.get(type).getShardIds();
				for (String shardId : shardIds)
				{
					PersistenceManager pm = getPersistenceManager(type, shardId);
					List<T> intermediateResult = pm.getBySql(clazz, sql, criteria);
					result.addAll(intermediateResult);
				}
				return result;
			}
			catch (QueryExecutionException e)
			{
				logger.info("Exception while trying to execute sql query" + sql + " on datastore " + type, e);
				continue;
			}
		}
		throw new QueryExecutionException("Failed to execute query, all related datastore instances are down");
	}

	public List<Map<String, Object>> getBySql(String sql, Map<String, Object> criteria) throws QueryExecutionException
	{
		boolean queryExecuted = false;
		List<Map<String, Object>> result;
		ReadConfig readConfig = getDefaultReadConfig();
		List<DataStoreType> readOrder = readConfig.getReadOrder();
		for (DataStoreType type : readOrder)
		{
			result = new ArrayList<Map<String, Object>>();
			List<String> shardIds = dataStoreConfigMap.get(type).getShardIds();
			for (String shardId : shardIds)
			{
				queryExecuted = false;
				PersistenceManager pm = getPersistenceManager(type, shardId);
				List<Map<String, Object>> intermediateResult = null;
				try
				{
					intermediateResult = pm.getBySql(sql, criteria);
					queryExecuted = true;
				}
				catch (QueryExecutionException e)
				{
					logger.info("Failed to execute query " + sql + " for datastore " + type, e);
					break;
				}
				result.addAll(intermediateResult);
			}
			if (queryExecuted)
			{
				return result;
			}
		}
		throw new QueryExecutionException("Failed to execute query " + sql + ".\nAll related data stores are down");
	}

	public <T extends Entity> List<T> getBySql(Class<T> clazz, Map<DataStoreType, String> sqlMap,
	        Map<String, Object> criteria) throws QueryExecutionException
	{
		boolean queryExecuted = false;
		List<T> result = null;
		ReadConfig readConfig = getReadConfigForEntity(clazz);
		List<DataStoreType> readOrder = readConfig.getReadOrder();
		for (DataStoreType type : readOrder)
		{
			String sql = sqlMap.get(type);
			result = new ArrayList<T>();
			List<String> shardIds = dataStoreConfigMap.get(type).getShardIds();
			for (String shardId : shardIds)
			{
				queryExecuted = false;
				PersistenceManager pm = getPersistenceManager(type, shardId);
				List<T> intermediateResult;
				try
				{
					intermediateResult = pm.getBySql(clazz, sql, criteria);
					queryExecuted = true;
				}
				catch (QueryExecutionException e)
				{
					logger.info("Failed to execute query " + sql + " for datastore" + type, e);
					break;
				}
				result.addAll(intermediateResult);
			}
			if (queryExecuted)
			{
				return result;
			}
		}
		throw new QueryExecutionException("Failed to execute query.\nAll related data stores are down");
	}

	public List<Map<String, Object>> getBySql(Map<DataStoreType, String> sqlMap, Map<String, Object> criteria)
	        throws QueryExecutionException
	{
		boolean queryExecuted = false;
		List<Map<String, Object>> result = null;
		ReadConfig readConfig = getDefaultReadConfig();
		List<DataStoreType> readOrder = readConfig.getReadOrder();
		for (DataStoreType type : readOrder)
		{
			String sql = sqlMap.get(type);
			result = new ArrayList<Map<String, Object>>();
			List<String> shardIds = dataStoreConfigMap.get(type).getShardIds();
			for (String shardId : shardIds)
			{
				queryExecuted = false;
				PersistenceManager pm = getPersistenceManager(type, shardId);
				List<Map<String, Object>> intermediateResult = null;
				try
				{
					intermediateResult = pm.getBySql(sql, criteria);
					queryExecuted = true;
				}
				catch (QueryExecutionException e)
				{
					logger.info("Failed to execute query " + sql + " for datastore" + type, e);
				}
				result.addAll(intermediateResult);
			}
			if (queryExecuted)
			{
				return result;
			}
		}
		throw new QueryExecutionException("Failed to execute query.\nAll related data stores are down");
	}
}