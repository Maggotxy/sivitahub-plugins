# 私有预发布压测器

该工具固定使用长期维护的 MIT 项目 Mineflayer，只允许在自己控制的预发布服务器上运行。默认仅能连接回环地址，机器人数量硬限制为 120。不要对任何第三方服务器使用。

测试账号固定为 `svtlt001` 至 `svtlt120`。压测必须在防火墙隔离的预发布副本进行，不能临时修改当前开放内测服的 AuthMe 或经济数据。AuthMe 正式配置每个 IP 最多注册 2 个账号，因此批量账号需要在预发布环境单独初始化：先把预发布 AuthMe 的 `maxRegPerIp` 临时提高到本轮数量，使用 `AUTHME_MODE=register` 运行一次短初始化，随后立即恢复为 `2`；正式阶梯测试只使用 `AUTHME_MODE=login`。不要把放宽后的配置复制回生产。

```bash
cd /home/lamb/minecraft/tools/load-test
npm ci --ignore-scripts
LOAD_TEST_ACK=private-server-only \
AUTHME_TEST_PASSWORD='replace-with-test-password' \
AUTHME_MODE=login \
BOT_COUNT=50 BOT_MODE=mixed DURATION_MINUTES=30 npm start
```

本机目标会自动读取 `server/runtime/plugins/RpgPerformance/status.json`，并在 `reports/` 生成权限为 `0600` 的 JSON 报告。报告包含客户端连接率、踢出/错误、P95/P99 MSPT、最低 TPS、峰值区块/实体/堆/JDBC 队列和负载状态次数；默认门槛为连接与出生率、零踢出/错误、P95 ≤ 45 ms、P99 ≤ 50 ms、最低 TPS ≥ 19、无 CRITICAL 和无 JDBC 等待。任一门槛失败进程以非零状态退出，不能再用“至少一个机器人进入”冒充整档通过。

从另一台内网机器发压时，设置 `ALLOW_REMOTE_TARGET=true`，并把只读性能状态文件通过受控共享挂载后传给 `PERFORMANCE_STATUS_FILE`。若确实无法共享，必须显式设置 `REQUIRE_PERFORMANCE_METRICS=false`；这次运行只能证明连接行为，不能作为容量结论。报告路径可用 `LOAD_TEST_REPORT` 指定。

分三轮执行 `BOT_COUNT=50`、`70`、`100`，每轮先做 `idle`，再做 `mixed`。压测客户端最好位于另一台内网机器；与 Paper 同机运行只能用于发现明显热点，不能作为容量结论。同步执行 `/rpgperf` 与 `/spark profiler start --timeout 600`，保存报告、spark 链接、GC 日志、JFR 和 PostgreSQL 慢查询。

通过门槛见 [性能文档](../../docs/PERFORMANCE.md)。
