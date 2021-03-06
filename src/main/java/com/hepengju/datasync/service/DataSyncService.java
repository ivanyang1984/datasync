package com.hepengju.datasync.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hepengju.datasync.config.ApplicationConfig;
import com.hepengju.datasync.config.DataSourceConfig;
import com.hepengju.datasync.config.DataSyncConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 数据同步服务
 * 
 * @author hepengju
 *
 */
@Service
public class DataSyncService {

	private Logger logger = LoggerFactory.getLogger(getClass());
	private Map<String,DataSource> cacheDataSourceMap = new HashMap<String, DataSource>();;
	
	@Autowired ApplicationConfig applicationConfig;
	
	/**
	 * 数据移动(同步)
	 */
	public void dataSync(DataSyncConfig dataSyncConfig) {
		logger.info("dataSync begin: " + dataSyncConfig);
		
		try (Connection srcConn = getConnection(dataSyncConfig.getSrcDbName());
			 Connection tarConn = getConnection(dataSyncConfig.getTarDbName());) {
			
				String srcTableName = dataSyncConfig.getSrcTableName();
				String tarTableName = dataSyncConfig.getTarTableName();
				
				//1.清空目标表
				tarConn.setAutoCommit(false);
				PreparedStatement delPs = tarConn.prepareStatement("DELETE FROM " + srcTableName);
				int delCount = delPs.executeUpdate();
				logger.info(srcTableName + " delCount: " + delCount);
				
				//2.取得源表的结果集
				PreparedStatement selPs = srcConn.prepareStatement("SELECT * FROM " + tarTableName);
				ResultSet rst = selPs.executeQuery();
				
				//3.批量插入目标表
				//3.1 拼接INSERT语句
				ResultSetMetaData metaData = rst.getMetaData();
				List<String> columnNameList = new ArrayList<>();
				int columnCount = rst.getMetaData().getColumnCount(); 
				for (int i = 1; i <= columnCount; i++) {
					String columnName = metaData.getColumnName(i);
					columnNameList.add(columnName);
				}
				String scol = columnNameList.stream().collect(Collectors.joining(","));               //列名逗号分隔
				String sque = columnNameList.stream().map(c -> "?").collect(Collectors.joining(",")); //问号逗号分隔
				String insSql = "INSERT INTO " + dataSyncConfig.getSrcTableName() + " (" + scol + ") VALUES (" + sque + ")";
				//3.2 批量插入
				PreparedStatement insPs = tarConn.prepareStatement(insSql);
				int readyCount = 0;
				int batchCount = 500;
				while (rst.next()) {
					for (int i = 1; i <= columnCount; i++) {
						insPs.setObject(i, rst.getObject(i));
					}
					insPs.addBatch();
					readyCount++;
					
					//最大数目设置
					if(dataSyncConfig.getMoveMaxCount() != null && readyCount >= dataSyncConfig.getMoveMaxCount()) {
						logger.info(tarTableName + " readyCount greater than moveMaxCount now");
						break;
					}
					
					if(readyCount % batchCount == 0) {
						insPs.executeBatch();
						logger.info(tarTableName + " readyCount: " + readyCount);
					}
				}
				
				insPs.executeBatch();
				tarConn.commit();
				logger.info(tarTableName + " readyCount: " + readyCount);
				
			} catch (SQLException e) {
				e.printStackTrace();
			}
		logger.info("dataSync end: " + dataSyncConfig);
	}
	
	/**
	 * 取得数据库连接
	 */
	public Connection getConnection(String dbName) throws SQLException {
		if(!cacheDataSourceMap.containsKey(dbName)) createDataSource(dbName);
		return cacheDataSourceMap.get(dbName).getConnection();
	}
	
	/**
	 * 创建数据源连接池
	 */
	public DataSource createDataSource(String dbName) {
		if(cacheDataSourceMap.containsKey(dbName)) return cacheDataSourceMap.get(dbName);
		
		DataSourceConfig dataSourceConfig = applicationConfig.getDataSourceConfigMap().get(dbName);
		
		HikariDataSource dataSource = new HikariDataSource();
		dataSource.setDriverClassName(dataSourceConfig.getDriverClassName());
		dataSource.setJdbcUrl(dataSourceConfig.getJdbcUrl());
		dataSource.setUsername(dataSourceConfig.getUsername());
		dataSource.setPassword(dataSourceConfig.getPassword());
		
		cacheDataSourceMap.put(dbName, dataSource);
		return dataSource;
	}

	/**
	 * 销毁数据源连接池
	 */
	public void destroyDataSource(String dbName) {
		if(cacheDataSourceMap.containsKey(dbName)) {
			HikariDataSource dataSource =  (HikariDataSource) cacheDataSourceMap.get(dbName);
			dataSource.close();
		}
	}
}
