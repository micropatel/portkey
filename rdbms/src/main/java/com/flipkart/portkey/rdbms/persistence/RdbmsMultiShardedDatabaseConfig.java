package com.flipkart.portkey.rdbms.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.flipkart.portkey.common.entity.Entity;
import com.flipkart.portkey.common.enumeration.ShardStatus;
import com.flipkart.portkey.common.exception.ShardNotAvailableException;
import com.flipkart.portkey.common.sharding.ShardIdentifier;
import com.flipkart.portkey.common.util.PortKeyUtils;
import com.flipkart.portkey.rdbms.metadata.RdbmsMetaDataCache;
import com.flipkart.portkey.rdbms.metadata.RdbmsTableMetaData;
import com.flipkart.portkey.rdbms.sharding.RdbmsShardIdentifier;

public class RdbmsMultiShardedDatabaseConfig implements RdbmsDatabaseConfig
{
	Map<String, RdbmsPersistenceManager> shardIdToPersistenceManagerMap;
	ShardIdentifier shardIdentifier = new RdbmsShardIdentifier();
	RdbmsMetaDataCache metaDataCache = RdbmsMetaDataCache.getInstance();
	private Map<String, ShardStatus> shardStatusMap;

	private List<String> getShardsAvailableForWrite()
	{
		List<String> liveShards = new ArrayList<String>();
		for (String shard : shardStatusMap.keySet())
		{
			if (shardStatusMap.get(shard).equals(ShardStatus.AVAILABLE_FOR_WRITE))
			{
				liveShards.add(shard);
			}
		}
		return liveShards;
	}

	@Override
	public <T extends Entity> RdbmsPersistenceManager getPersistenceManager(T bean) throws ShardNotAvailableException
	{
		RdbmsTableMetaData metaData = metaDataCache.getMetaData(bean.getClass());
		String shardKeyFieldName = metaData.getShardKeyFieldName();
		String shardKey = PortKeyUtils.toString(PortKeyUtils.getFieldValueFromBean(bean, shardKeyFieldName));
		List<String> liveShards = getShardsAvailableForWrite();
		String shardId = shardIdentifier.getShardId(shardKey, liveShards);
		return shardIdToPersistenceManagerMap.get(shardId);
	}

	@Override
	public <T extends Entity> RdbmsPersistenceManager getPersistenceManager(String shardKey)
	{
		return shardIdToPersistenceManagerMap.get(shardKey);
	}

	@Override
	public <T extends Entity> List<RdbmsPersistenceManager> getAllPersistenceManagers()
	{
		return new ArrayList<RdbmsPersistenceManager>(shardIdToPersistenceManagerMap.values());
	}

	public void setShardIdToPersistenceManagerMap(Map<String, RdbmsPersistenceManager> shardIdToPersistenceManagerMap)
	{
		this.shardIdToPersistenceManagerMap = shardIdToPersistenceManagerMap;
	}

	public void addToShardIdToPersistenceManagerMap(String shardId, RdbmsPersistenceManager persistenceManager)
	{
		this.shardIdToPersistenceManagerMap.put(shardId, persistenceManager);
	}

	@Override
	public ShardIdentifier getShardIdentifier()
	{
		return shardIdentifier;
	}

	@Override
	public <T extends Entity> T generateShardIdAndUpdateBean(T bean) throws ShardNotAvailableException
	{
		String shardKeyFieldName = metaDataCache.getShardKeyFieldName(bean.getClass());
		String shardKey = PortKeyUtils.toString(PortKeyUtils.getFieldValueFromBean(bean, shardKeyFieldName));
		List<String> liveShards = getShardsAvailableForWrite();
		String shardId = shardIdentifier.generateShardId(shardKey, liveShards);
		String newShardKey = shardIdentifier.generateNewShardKey(shardKey, shardId);
		PortKeyUtils.setFieldValueInBean(bean, shardKeyFieldName, newShardKey);
		return bean;
	}

	@Override
	public void healthCheck()
	{
		// TODO: set this status into hazelcast
		for (RdbmsPersistenceManager persistenceManager : shardIdToPersistenceManagerMap.values())
		{
			ShardStatus status = persistenceManager.healthCheck();
		}
	}
}
