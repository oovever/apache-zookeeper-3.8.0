package com.zookeeper.test;


import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;

public class ZkClientDemo {
    private final static  String CLUSTER_CONNECT_STR="127.0.0.1:2181";

    public static void main(String[] args) throws Exception {
        //构建客户端实例
        CuratorFramework curatorFramework= CuratorFrameworkFactory.builder()
                .connectString(CLUSTER_CONNECT_STR)
                .sessionTimeoutMs(4000)
                .retryPolicy(new ExponentialBackoffRetry(1000,3)).
                namespace("")
                .build();
        //启动客户端
        curatorFramework.start();
        System.out.println(111);
//        String path = curatorFramework.create().forPath("/curator-node");
        String path =curatorFramework.create().withMode(CreateMode.EPHEMERAL).forPath("/roc_EPHEMERAL", "data".getBytes());
        System.out.println("curator create node :" + path);
//        Stat stat=new Stat();
//        //查询节点数据
//        byte[] bytes = curatorFramework.getData().storingStatIn(stat)
//                .forPath("/user");
//        System.out.println(new String(bytes));

        curatorFramework.close();
    }
}
