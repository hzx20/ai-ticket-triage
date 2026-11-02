FROM eclipse-temurin:17-jre-noble

WORKDIR /app

# 后端产物是 Spring Boot 分层结构：bin（薄 jar）+ lib（383 个依赖）+ config（yml 配置）
# 薄 jar 的 MANIFEST 里写的是相对路径 ../lib/xxx.jar、../config/，所以三者必须保持同级关系
COPY app/ /app/

RUN mkdir -p /app/logs /app/data/file

WORKDIR /app/bin

# 内存参数说明：
# -Xms512m / -Xmx1024m  固定堆上下限，避免 JVM 在 3.7G 机器上按比例取到过大的值
# -XX:MaxMetaspaceSize  限制类元数据上限，防缓慢泄漏
# HeapDumpOnOutOfMemoryError  OOM 时落堆转储，方便事后定位
EXPOSE 18000 17889

ENTRYPOINT ["java", \
  "-Xms512m", \
  "-Xmx1024m", \
  "-XX:MetaspaceSize=128m", \
  "-XX:MaxMetaspaceSize=256m", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/app/logs/oom.hprof", \
  "-XX:ErrorFile=/app/logs/hs_err_pid%p.log", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Duser.timezone=Asia/Shanghai", \
  "-Dfile.encoding=UTF-8", \
  "-jar", "continew-admin.jar"]
