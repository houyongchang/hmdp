package com.hmdp.utils;

public interface Ilock {
    //获取锁
    boolean tryLock(long timeOut);
    //释放锁
    void unLock();
}
