pipeline {
    agent {
        kubernetes {
            cloud "Redrock-Cloud"
            yaml """
apiVersion: v1
kind: Pod
metadata:
  labels:
    component: jenkins-agent
spec:
  containers:
    - name: sonar-scanner
      image: sonarsource/sonar-scanner-cli:latest
      command:
        - cat
      tty: true
    - name: builder
      image: beatrueman/builder:1.0
      securityContext:
        privileged: true
      command:
        - cat
      tty: true
    - name: deployer
      image: beatrueman/deployer:1.0
      command:
        - cat
      tty: true
    - name: jnlp
      image: jenkins/inbound-agent:3206.vb_15dcf73f6a_9-2
      resources:
        limits:
          memory: "1Gi"
          cpu: "200m"
"""
        }
    }

    // environment {
    //     HARBOR_REGISTRY = ''
    //     PROJECT_NAME = 'myapp' // 项目名称，必须小写
    //     ENTRYPOINT = 'main.py' // 项目入口文件，app.py或main.py
    //     PORT = '6666' // 项目暴露的端口
    //     IMAGE_NAME = 'yicloud' // 镜像名称
    //     TAG = 'v1' // 镜像标签
    //     SONAR_PROJECT_NAME = 'Python' // sonar项目名称
    // }

    stages {
        stage('git clone') {
            steps {
                echo "1.正在克隆代码......"
                git url: ""
            }
        }

        stage('SonarQube code checking') {
            steps {
                container('sonar-scanner') {
                    echo '2.正在进行代码检查......'
                    echo '代码检查结果请在面板查看！'
                    withSonarQubeEnv('SonarQube') {
                        sh '''sonar-scanner \
                            -Dsonar.projectKey=${SONAR_PROJECT_NAME} \
                            -Dsonar.projectName=${SONAR_PROJECT_NAME} \
                            -Dsonar.sources="/home/jenkins/agent/workspace/${JOB_NAME}" \
                            -Dsonar.projectVersion=1.0 \
                            -Dsonar.sourceEncoding=UTF-8
                        '''
                    }
                }
            }
        }

        stage('Image build') {
            steps {
                script {
                    echo '3.正在制作镜像......'
                    try {
                        def result = sh(script: "ls -al | grep Dockerfile", returnStatus: true)
                        if (result == 0) {
                            echo "找到 Dockerfile，开始构建镜像"
                            container('builder') {
                                sh '''
                                    draft create -a myapp --variable PORT=${PORT} \
                                        --variable APPNAME=${PROJECT_NAME} \
                                        --variable SERVICEPORT=8088 \
                                        --variable NAMESPACE=test \
                                        --variable IMAGENAME=${HARBOR_REGISTRY}/library/${IMAGE_NAME} \
                                        --variable IMAGETAG=${TAG} \
                                        --variable ENTRYPOINT=${ENTRYPOINT} \
                                        --variable VERSION=3 \
                                        --deploy-type helm \
                                        --deployment-only
                                    rm "/home/jenkins/agent/workspace/${JOB_NAME}/charts/templates/namespace.yaml"
                                    buildah bud -t ${HARBOR_REGISTRY}/library/${IMAGE_NAME}:${TAG} .
                                '''
                            }
                        } else {
                            echo "未找到 Dockerfile，将生成 Dockerfile"
                            container('builder') {
                                def jobNameLower = "${JOB_NAME}".toLowerCase()
                                sh '''
                                    draft create -a myapp --variable PORT=${PORT} \
                                        --variable APPNAME=${PROJECT_NAME} \
                                        --variable SERVICEPORT=8088 \
                                        --variable NAMESPACE=test \
                                        --variable IMAGENAME=${HARBOR_REGISTRY}/library/${IMAGE_NAME} \
                                        --variable IMAGETAG=${TAG} \
                                        --variable ENTRYPOINT=${ENTRYPOINT} \
                                        --variable VERSION=3 \
                                        --deploy-type helm
                                    rm "/home/jenkins/agent/workspace/${JOB_NAME}/charts/templates/namespace.yaml"
                                    buildah bud -t ${HARBOR_REGISTRY}/library/${IMAGE_NAME}:${TAG} .
                                '''
                            }
                        }
                    } catch (err) {
                        echo "查找 Dockerfile 发生错误: ${err}"
                    }
                }
            }
        }

        stage('Image push') {
            steps {
                echo "3. Pushing image"
                container('builder') {
                    script {
                        withCredentials([usernamePassword(credentialsId: 'Redrock-Harbor-Secret', passwordVariable: 'passwd', usernameVariable: 'username')]) {
                            sh "buildah login -u ${username} -p ${passwd} ${env.HARBOR_REGISTRY}"
                            sh "buildah images"
                            sh "buildah push ${HARBOR_REGISTRY}/library/${IMAGE_NAME}:${TAG}"
                        }
                    }
                }
            }
        }
        
        stage('Deploy to Kubernetes') {
            steps {
                echo "5.即将把服务部署在Kubernetes集群上......"
                container('deployer') {
                    script {
                        // 使用受限制的kubeconfig
                        def kubeConfigCreds = credentials('kubeconfig')
                        // 写入临时kubeconfig文件
                        sh 'echo "${kubeConfigCreds}" | base64 --decode > /tmp/kubeconfig.yaml'
                        sh 'ls'

			            // 部署并获取返回码，如果成功部署则打包chart并上传至Harbor仓库
                        def chartYamlContent = readFile "charts/Chart.yaml" // 读取Chart.yaml
                        def chartYaml = readYaml text: chartYamlContent
                        def chartVersion = chartYaml.version // 获取Chart的version
                        def packageName = "${PROJECT_NAME}-${chartVersion}.tgz" // 打包后的Chart名
                        
                        def helmDeployResult = sh(script: "helm install ${PROJECT_NAME} -n test --kubeconfig /tmp/kubeconfig.yaml charts", returnStatus: true)
			                if(helmDeployResult == 0) {
			                    echo '部署成功！'
			                    echo '正在打包Chart，并上传至Harbor仓库'
			                    
			                    sh "helm package charts"
			                    withCredentials([usernamePassword(credentialsId: 'Redrock-Harbor-Secret', passwordVariable: 'passwd', usernameVariable: 'username')]) {
			                        sh "helm registry login ${env.HARBOR_REGISTRY} -u ${username} -p ${passwd}"
			                        sh "helm push ${packageName} oci://${HARBOR_REGISTRY}/library"
			                    }
			                    
			                } else {
			                    error "Chart部署失败，Helm返回码: ${helmDeployResult}"
			                }
			                
                    }
                }
            }
        }
    }
}