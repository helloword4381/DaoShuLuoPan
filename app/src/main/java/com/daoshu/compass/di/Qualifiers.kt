package com.daoshu.compass.di

import javax.inject.Qualifier

/** 注入 IO 调度器（网络、磁盘、传感器回调） */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** 注入默认调度器（CPU 计算） */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** 注入与应用同生命周期的 CoroutineScope */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
