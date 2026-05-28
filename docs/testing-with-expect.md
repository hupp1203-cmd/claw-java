# 用 expect 做交互式测试

`expect` 是一个自动化 TTY 交互的工具，适合测试需要伪终端的 CLI 程序（如 claw）。

## 安装

```bash
# macOS
brew install expect

# Ubuntu/Debian
apt-get install expect
```

## 基本语法

```tcl
#!/usr/bin/expect -f

set timeout 30          # 全局超时秒数

spawn java -jar app.jar # 启动进程（相当于在终端里运行）

expect ">"              # 等待输出中出现 ">"，出现后继续
send "hello\r"          # 发送输入（\r 相当于回车）

expect eof              # 等待进程退出
```

## 四个核心命令

| 命令 | 作用 |
|------|------|
| `spawn <cmd>` | 启动子进程并接管其 TTY |
| `expect "<pattern>"` | 阻塞直到输出匹配（支持正则） |
| `send "<text>"` | 向子进程发送文本 |
| `interact` | 把控制权还给用户（手动接管） |

## 测试 claw 的模板

```tcl
#!/usr/bin/expect -f

set timeout 120
log_file -noappend /tmp/claw_test.log   # 全部输出写入日志

spawn java -Xmx512m -jar /path/to/claw-cli.jar

# 等待启动完成（出现 > 提示符）
expect {
    ">"       { }
    timeout   { puts "ERROR: 启动超时"; exit 1 }
}

sleep 1

# 发送一个问题
send "你好\r"

# 等待 claw 回答完毕（再次出现提示符）
expect {
    -timeout 60
    ">"       { }
    timeout   { puts "ERROR: 响应超时"; exit 1 }
}

# 退出
send "/exit\r"
expect eof

puts "=== 测试完成 ==="
```

运行：

```bash
expect test.expect
```

## 多步骤测试

把每个对话轮次顺序写下来即可：

```tcl
# 第一轮
send "介绍一下你自己\r"
expect { -timeout 30 ">" { } timeout { exit 1 } }

# 第二轮（复杂任务给更长超时）
send "用 dispatch_agents 并行查找所有 Tool 实现类\r"
expect { -timeout 180 ">" { } timeout { exit 1 } }

# 退出
send "/exit\r"
expect eof
```

## 断言：检查输出内容

`expect` 本身不做断言，用变量捕获输出后在 Tcl 里检查：

```tcl
# 捕获本轮输出
expect {
    -timeout 60
    -re "(.*\n)*.*>" {
        set output $expect_out(buffer)
    }
    timeout { exit 1 }
}

# 检查关键词
if {[string match "*Paris*" $output]} {
    puts "PASS: 包含 Paris"
} else {
    puts "FAIL: 没有找到 Paris"
    exit 1
}
```

## 常见问题

**乱码**：`spawn` 会继承终端编码，确保环境变量 `LANG=en_US.UTF-8` 或直接在脚本顶部加：

```tcl
set env(LANG) "en_US.UTF-8"
```

**超时太短**：复杂任务（如 dispatch_agents 分析项目）可能需要 2~3 分钟，`-timeout 180` 单独覆盖某个 `expect`。

**调试输出太多**：`log_file` 的内容包含控制字符（颜色码等），查看时用：

```bash
cat -v /tmp/claw_test.log    # 显示控制字符
sed 's/\x1b\[[0-9;]*m//g' /tmp/claw_test.log  # 去掉 ANSI 颜色
```
