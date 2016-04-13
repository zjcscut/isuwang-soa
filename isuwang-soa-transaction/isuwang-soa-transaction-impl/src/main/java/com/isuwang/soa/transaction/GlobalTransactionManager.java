package com.isuwang.soa.transaction;

import com.isuwang.soa.core.metadata.Service;
import com.isuwang.soa.remoting.fake.json.JSONPost;
import com.isuwang.soa.remoting.fake.metadata.MetadataClient;
import com.isuwang.soa.remoting.filter.LoadBalanceFilter;
import com.isuwang.soa.transaction.api.domain.*;
import com.isuwang.soa.transaction.db.action.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.bind.JAXB;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global Transaction Manager
 *
 * @author craneding
 * @date 16/4/12
 */
public class GlobalTransactionManager {

    Logger LOGGER = LoggerFactory.getLogger(GlobalTransactionManager.class);

    private AtomicBoolean working = new AtomicBoolean(false);

    @Transactional(value = "globalTransaction", rollbackFor = Exception.class)
    public void doJob() {
        if (working.get())
            return;

        working.set(true);

        try {
            LOGGER.info("--- 定时事务管理器开始 ---");

            List<TGlobalTransaction> globalTransactionList = new GlobalTransactionsFindAction().execute();

            for (TGlobalTransaction globalTransaction : globalTransactionList) {

                List<TGlobalTransactionProcess> transactionProcessList = new GlobalTransactionProcessFindAction(globalTransaction.getId()).execute();

                int i = 0;
                for (; i < transactionProcessList.size(); i++) {

                    TGlobalTransactionProcess process = transactionProcessList.get(i);

                    //更新process的期望状态为“已回滚”
                    if (process.getExpectedStatus() != TGlobalTransactionProcessExpectedStatus.HasRollback)
                        new GlobalTransactionProcessExpectedStatusUpdateAction(process.getId(), TGlobalTransactionProcessExpectedStatus.HasRollback).execute();

                    String responseJson;
                    Service service = null;
                    //call roll back method
                    try {
                        //获取服务的metadata
                        String metadata = "";
                        metadata = new MetadataClient(process.getServiceName(), process.getVersionName()).getServiceMetadata();
                        if (metadata != null) {
                            try (StringReader reader = new StringReader(metadata)) {
                                service = JAXB.unmarshal(reader, Service.class);
                            }
                        }

                        //获取服务的ip和端口
                        String callerInfo = LoadBalanceFilter.getCallerInfo(process.getServiceName(), process.getVersionName(), process.getRollbackMethodName());
                        if (callerInfo != null) {

                            String[] infos = callerInfo.split(":");

                            JSONPost jsonPost = new JSONPost(infos[0], Integer.valueOf(infos[1]));
                            //调用回滚方法
                            responseJson = jsonPost.callServiceMethod(process.getServiceName(), process.getVersionName(), process.getRollbackMethodName(), process.getRequestJson(), service);

                            //更新事务过程表为已回滚
                            new GlobalTransactionProcessUpdateAction(process.getId(), responseJson, TGlobalTransactionProcessStatus.HasRollback).execute();
                        }

                    } catch (Exception e) {

                        //更新事务过程表的重试次数和下次重试时间
                        new GlobalTransactionProcessUpdateAfterRollbackFail(process.getId()).execute();

                        LOGGER.error(e.getMessage(), e);
                        break;
                    }

                }

                if (i == transactionProcessList.size()) {
                    //已回滚
                    new GlobalTransactionUpdateAction(globalTransaction.getId(), i > 0 ? transactionProcessList.get(i - 1).getTransactionSequence() : 0, TGlobalTransactionsStatus.HasRollback).execute();

                } else {
                    //部分回滚
                    new GlobalTransactionUpdateAction(globalTransaction.getId(), i > 0 ? transactionProcessList.get(i - 1).getTransactionSequence() : 0, TGlobalTransactionsStatus.PartiallyRollback).execute();
                }
            }
            LOGGER.info("--- 定时事务管理器结束 ---");
        } finally {
            working.set(false);
        }
    }
}
