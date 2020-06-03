#!/bin/bash

#脚本参数，$1:ci路径   $2:服务名称   $3:打包版本   $4:代码哈希  $5 项目名称  $6:项目类型(Html  or  Java  or  Nodejs)  $7:生产仓库(可选)
#私库地址
REGISTRY='192.168.137.5'
#私库存放服务的仓库名
WAREHOUSE_NAME='huisebug'
#项目的CI目录名称
if [ x$2 != xnone ];then
	app_name=$5-$2
	ci_dir=$2-ci
else
	app_name=$5
	ci_dir=$5-ci
fi	

#java项目镜像制作
java()
{
BASE_IMAGE=${REGISTRY}/base/openjdk:8
APP_HOME=/data/projects/${app_name}
EXE_CMD="java -jar"
EXE_BIN=${APP_HOME}/bin/${app_name}.jar
EXE_CONF='-Dlog_host=${log_host}'
#jvm内存参数，临时设置，后续可以传参自定义
EXE_LEVEL="-Xms1680M -Xmx1680M -Xmn1260M -Xss1M"
#jvm其他参数调优
EXE_OPTION="-server -XX:SurvivorRatio=8 -XX:+UseConcMarkSweepGC -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m -XX:MaxDirectMemorySize=1g -XX:+ExplicitGCInvokesConcurrent -XX:CMSInitiatingOccupancyFraction=80 -XX:-UseCMSInitiatingOccupancyOnly -Dsun.rmi.dgc.server.gcInterval=2592000000 -Dsun.rmi.dgc.client.gcInterval=2592000000 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${APP_HOME}/log/java.hprof -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintGCTimeStamps"



cat > run.sh << \EOF
#!/bin/bash

#环境变量当前主机名
log_host=`hostname`
#jvm内存参数设置
java_mem=`env|grep java_mem|awk -F '=' '{print $2}'`
#服务日志是否在控制台输出，即容器即生命
log_echo=`env|grep log_echo|awk -F '=' '{print $2}'`

#判断变量java_mem是否为空，不存在即为空
if [[ x$java_mem == x ]];then
EXE_LEVEL=""
else
EXE_LEVEL=$java_mem
fi

#额外的jvm参数
if [[ x$general_para != x ]];then
EXT_OPTION=$general_para
else
EXT_OPTION=""
fi

#程序的参数
if [[ x$application_para != x ]];then
APP_OPTION=$application_para
else
APP_OPTION=""
fi

EOF

echo "if [[ x\${log_echo} != x ]];then" >> run.sh
echo ${EXE_CMD} \${EXE_LEVEL} ${EXE_OPTION}  \${EXT_OPTION} ${EXE_CONF} ${EXE_BIN} \${APP_OPTION} >> run.sh
echo "else" >> run.sh
EXE_CMD="nohup java -jar" 
echo ${EXE_CMD} \${EXE_LEVEL} ${EXE_OPTION}  \${EXT_OPTION} ${EXE_CONF} ${EXE_BIN} \${APP_OPTION} >> run.sh
echo "tail -f /dev/null" >> run.sh
echo "fi" >> run.sh


#准备好将要复制到镜像中的服务文件
rm -rf source
mkdir -p source
mv ../${ci_dir}/target/* source/

#生成Dockerfile
cat > Dockerfile << EOF

FROM ${BASE_IMAGE} 
RUN for dir in data bin log conf; do mkdir -p ${APP_HOME}/\$dir; done 
ADD source ${APP_HOME}/bin
COPY run.sh /root/run.sh
CMD ["/bin/bash","/root/run.sh"]

EOF

}
#web项目镜像制作，包含nodejs和html
web()
{
#此处只做拷贝文件，启动方式按照$BASE_IMAGE的说明进行
BASE_IMAGE=${REGISTRY}/base/nginx:1.19
APP_HOME=/usr/share/nginx/html

#准备好将要复制到镜像中的服务文件，nodejs打包后的项目html全部放在dist目录下，在前面的pipline中已经将dist目录移动到了target目录中
rm -rf source
mkdir -p source
mv ../${ci_dir}/target/dist/* source/


#生成dockerfile
cat > Dockerfile << EOF
FROM ${BASE_IMAGE}
ADD source ${APP_HOME}
EOF

}


if [[ x$6 == xJava || x$6 == xjava  ]];then
java $1 $2 $3 $4 $5 $6 $7 $8
else
web $1 $2 $3 $4 $5 $6 $7 $8
fi

#####################main()###################
cat Dockerfile
container_name=${app_name}
time_char=`date "+%y%m%d%H%M"`
container_version=$3-${time_char}$4
#私有仓库的认证
docker login 192.168.137.5 -u admin -p huisebug >/dev/null 2>&1
docker build -t ${REGISTRY}/${WAREHOUSE_NAME}/${container_name}:${container_version} .

#生成镜像文件#推送镜像文件
docker push  ${REGISTRY}/${WAREHOUSE_NAME}/${container_name}:${container_version}

if [[ x$7 == xgo ]];then
echo '开始推送到生产镜像仓库'
#生产镜像仓库地址
proimagerepo=192.168.137.5
#生产镜像仓库认证
docker login ${proimagerepo} -u admin -p huisebug >/dev/null 2>&1
docker tag ${REGISTRY}/${WAREHOUSE_NAME}/${container_name}:${container_version} ${proimagerepo}/${WAREHOUSE_NAME}/${container_name}:${container_version}
docker push ${proimagerepo}/${WAREHOUSE_NAME}/${container_name}:${container_version}
echo '清除多余的标签'
docker rmi ${proimagerepo}/${WAREHOUSE_NAME}/${container_name}:${container_version}
fi