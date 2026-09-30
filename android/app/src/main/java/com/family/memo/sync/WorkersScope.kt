package com.family.memo.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 应用级后台协程作用域（提醒调度、小组件刷新等不依赖界面的轻工作）。 */
object WorkersScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)
