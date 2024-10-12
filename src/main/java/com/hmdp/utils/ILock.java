package com.hmdp.utils;

public interface ILock {
    boolean tryLock(Long time);
    void unlock();
}
