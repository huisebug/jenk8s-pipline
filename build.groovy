//当前的jenkins管理节点
node {
//配置全局变量
env.project = env.JOB_BASE_NAME.split("\\+")[0]
env.app_name = env.JOB_BASE_NAME.split("\\+")[1]
env.branch = env.JOB_BASE_NAME.split("\\+")[2]
println(env.project)
println(env.app_name)
println(env.branch)

/*
因为jenkins的版本原因，使用git方式拉取了jenkinsfile的项目所有源代码文件
在系统workspace目录下建立了一个job名+@script的文件目录来存放jenkinsfile源代码所有文件
pipline执行的时候是以job名称为文件目录名称下
类似于
[root@localhost workspace]# tree
.
├── jenkins+java+master
│   └── java-ci
├── jenkins+java+master@script
│   ├── build.groovy
│   └── projects
│       └── jenkins
│           └── project_info
*/

//jenkinsfile的源代码存放目录名称,此处是:job名称+@script
env.tempsuffix = '@script'
env.JenkinsfileREPO = env.JOB_BASE_NAME + env.tempsuffix

//获取代码仓库地址,作为全局变量提供给打包k8s节点使用
if (env.app_name == '') {
	env.app_name = 'none'
	env.git_repository = sh returnStdout: true, script: 'cat ../'+env.JenkinsfileREPO+'/projects/'+env.project+'project_info|grep '+env.project+'_repo|awk -F "=" \'{print $2}\''
	env.ci_dir =  env.project+'-ci'

} else {
	env.git_repository = sh returnStdout: true, script: 'cat ../'+env.JenkinsfileREPO+'/projects/'+env.project+'/project_info|grep '+env.project+'#'+env.app_name+'_repo|awk -F "=" \'{print $2}\''    
	env.ci_dir =  env.app_name+'-ci'
}
}

//CI打包k8s节点,这里就是匹配之前的pod template中的标签列表
node('jenkinsbuildnode') {
	echo "本次build的项目的源代码地址: "+env.git_repository
        
		stage('CI打包') {
		    /*tools {
                maven '3.6.2'
                jdk '1.8.0_242'
                nodejs '10.19.0'
				npm '5.8.0'
            }*/
			
			
			script {			       
                    /*判断是否推送到生产仓库
                    try {
                        timeout(time: 70, unit: 'SECONDS') {
                            def userInput = input(id: 'userInput', ok: '确定', message: '是否推送镜像到生产环境', parameters: [booleanParam(defaultValue:  false, description: '', name: '发布到master仓库')])
                            //println userInput
                            println userInput.getClass()
                            if(userInput==true){
                                env.to_master_registry = "go"
                            }else {
                                env.to_master_registry = ""
                            }
                        }
                    }
					catch(Exception ex) {
                        println("Catching  exception")
                        echo 'do nothing, continue.......'
                        env.to_master_registry = ""
                    }*/
					dir(env.ci_dir) {
                        echo "拉取git代码"
                        checkout([$class: 'GitSCM', branches: [[name: branch]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'huisebug', url: git_repository]]])                    
                        git_commit_hash = sh (script: "git log -n 1 --pretty=format:'%H'", returnStdout: true)
                        env.git_commit_hash = '-'+git_commit_hash[0..4]                        

                        project = sh returnStdout: true, script: 'cat app.info|grep devlang|awk -F ":" \'{print $2}\''
                        project = project.replace("\r","").replace(" ", "").replace('\n', '')
                        echo "项目类型是"+project
                        println project.class

                        def version = sh returnStdout: true, script: ''' cat app.info |grep version|awk -F ":" '{print $2}' '''
                        env.ver = version.replace("\r", "").replace("\n", "").replace(" ", "")
                        echo '打包版本'+env.ver
                        
						if (project == 'NodeJs'||project == 'nodejs') {
                            sh 'mkdir -pv target'
                            //sh 'find ./ -type f -exec dos2unix -q {} \\;'
                            sh 'npm cache clean --force'
                            sh 'npm config set registry http://registry.npm.taobao.org/'
                            sh 'cnpm install'
                            if (env.JOB_BASE_NAME.tokenize('+')[3] == 'dev') {
                                sh 'npm run build:dev'
                            } else if (env.JOB_BASE_NAME.tokenize('+')[3] == 'test') {
                                sh 'npm run build:test'
                            } else if (env.JOB_BASE_NAME.tokenize('+')[3] == 'sg') {
                                sh 'npm run build:sg'                               
                            } else {
                                sh 'npm run build'
                            }
                            sh 'mv dist target/'
                            echo project+'nodejs container Preparing....'
							
                        } else if (project == 'Java'||project == 'java') {
                            echo project+' java container Preparing....'
                            sh 'mvn -Dmaven.test.skip=true clean package'   
							
                        } else if (project == 'Python'||project == 'python') {
                            sh 'mkdir -pv target'
                            //sh 'find ./ -type f -exec dos2unix -q {} \\;'
                            sh 'cp -rf `ls|grep -v app.info|grep -v target|xargs` target/'
                            echo project+' python container Preparing....'
							
                        } else if (project == 'HTML'||project == 'html') {
                            sh 'mkdir -pv target/dist'
                            //sh 'find ./ -type f -exec dos2unix -q {} \\;'
                            sh 'cp -rf `ls|grep -v app.info|grep -v target|xargs` target/dist'
                            echo project+' html container Preparing....'
							
                        } else {
                            sh 'mkdir -pv target/dist'
                            //sh 'find ./ -type f -exec dos2unix -q {} \\;'
                            sh 'cp -rf `ls|grep -v app.info|grep -v target|xargs` target/dist'
                            echo project+' html container Preparing....'
						}
					}			
			}
		}
		stage('docker镜像制作') {
            script {
                sh 'mkdir -pv dockerbuild'
                dir('dockerbuild') {
                    deleteDir()
                    echo "拉取docker镜像制作脚本，代码分支一般默认是master"
					env.git_repo = 'https://github.com/huisebug/jenk8s-pipline.git'
                    checkout([$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'huisebug', url: git_repo]]])                    
						
                    println('需要执行打包的容器名称有:')
                    sh 'bash scripts/dockerbuild.sh'+' '+env.WORKSPACE+'/'+env.ci_dir+' '+env.app_name+' '+env.ver+' '+env.git_commit_hash+' '+env.project+' '+project+' '+env.to_master_registry
                }	
            }
        }		
}		