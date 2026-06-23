#!/bin/bash
cd "$(dirname "$0")"

PORT=1888
LOG_FILE="app.log"
PID_FILE="app.pid"
DB_PATH="${STORY_DB_PATH:-./data}"

echo `date`

# 检查端口是否已被占用，如果是则杀掉旧进程
if lsof -i :$PORT -sTCP:LISTEN >/dev/null 2>&1; then
    OLD_PID=$(lsof -ti :$PORT -sTCP:LISTEN)
    echo "端口 $PORT 已被占用 (PID: $OLD_PID)，正在停止旧进程..."
    kill $OLD_PID 2>/dev/null
    sleep 2
    # 如果还没停掉，强制杀
    if kill -0 $OLD_PID 2>/dev/null; then
        kill -9 $OLD_PID 2>/dev/null
        sleep 1
    fi
    echo "旧进程已停止"
fi

echo "Starting Story Creator in background..."
echo "数据库路径: ${DB_PATH}/story-creator"
echo "  (设置环境变量 STORY_DB_PATH 可自定义，例如: STORY_DB_PATH=/var/data ./start_web.sh)"
nohup mvn clean spring-boot:run -Dspring-boot.run.arguments="--server.port=$PORT --spring.datasource.url=jdbc:h2:file:${DB_PATH}/story-creator" > "$LOG_FILE" 2>&1 &
APP_PID=$!
echo "进程 PID: $APP_PID"

# 等待启动完成，最多等待60秒
echo -n "等待启动"
for i in $(seq 1 60); do
    sleep 1
    echo -n "."
    if curl -s -o /dev/null -w "" http://localhost:$PORT/ 2>/dev/null; then
        HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$PORT/)
        if [ "$HTTP_CODE" = "200" ]; then
            echo ""
            echo "启动成功!"
            echo "访问地址: http://localhost:$PORT"
            echo "日志文件: $LOG_FILE"
            echo "停止命令: kill $APP_PID"
            echo "$APP_PID" > "$PID_FILE"
            exit 0
        fi
    fi
    # 检查进程是否还在运行
    if ! kill -0 $APP_PID 2>/dev/null; then
        echo ""
        echo "启动失败! 进程已退出，请查看日志:"
        tail -20 "$LOG_FILE"
        exit 1
    fi
done

echo ""
echo "启动超时! 请查看日志: $LOG_FILE"
exit 1
