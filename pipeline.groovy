
pipeline{
    agent any
    tools{
        jdk 'jdk17'
        nodejs 'node16'
    }
    environment {
        SCANNER_HOME=tool 'sonar-scanner'
    }
    stages {
        stage('clean workspace'){
            steps{
                cleanWs()
            }
        }
        stage('Checkout from Git'){
            steps{
                git branch: 'main', url: 'https://github.com/khizarsheraz/Netflix.git'
            }
        }
        stage("Sonarqube Analysis "){
            steps{
                withSonarQubeEnv('sonar-server') {
                    sh ''' $SCANNER_HOME/bin/sonar-scanner -Dsonar.projectName=Netflix \
                    -Dsonar.projectKey=Netflix ''' 
                }
            }
        }

        // stage("quality gate"){
        //    steps {
        //         script {
        //             waitForQualityGate abortPipeline: false, credentialsId: 'Sonar-token' 
        //         }
        //     } 
        // }

// i've commented quality gate it because on my machine it was taking too much time.
        stage('Install Dependencies') {
            steps {
                sh "npm install"
            }
        }
        stage('OWASP  Dependency Check FS SCAN') {
            steps {
                dependencyCheck additionalArguments: '--scan ./ --disableYarnAudit --disableNodeAudit', odcInstallation: 'DP-Check'
                dependencyCheckPublisher pattern: '**/dependency-check-report.xml'
            }
        }
        stage('TRIVY FS SCAN - Vulnerability Scan - Docker File') {
            steps {
                sh "trivy fs . > trivyfs.txt"
            }
        }

        stage('OPA Conftest - Docker File Scan'){
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                sh 'sudo conftest test --policy /root/Netflix/Opa-Docker-Security.rego /root/Netflix/Dockerfile'
                
                }
            }
        }   
        stage("Docker Build & Push"){
            steps{
                script{
                    withDockerRegistry(credentialsId: 'docker', toolName: 'docker'){   
                       sh "sudo docker build --build-arg TMDB_V3_API_KEY=4deef21ee71793e3884296430a8e9a90 -t netflix ."
                       sh "sudo docker tag netflix khizarsheraz/netflix:latest "
                       sh "sudo docker push khizarsheraz/netflix:latest "
                    }
                }
            }
        }
        stage("TRIVY Docker Image Scan"){
            steps{
                sh "trivy image khizarsheraz/netflix:latest > trivyimage.txt" 
            }
        }
        stage('Deploy to container'){
            steps{
                sh 'docker run -d -p 8081:80 khizarsheraz/netflix:latest'
            }
        }

        
        stage('OPA Conftest - Kubernetes File Scan'){
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                sh 'sudo conftest test --policy /root/Netflix/opa-k8s-security.rego /root/Netflix/Kubernetes/deployment.yml'
                }
            }
        } 

       stage('KubeSec - Kubernetes Vulnerability scan'){
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                sh 'sudo kubesec scan /root/Netflix/Kubernetes/deployment.yml'
                } 
            }
        } 


        stage('Deploy to kubernets'){
            steps{
                script{
                    dir('Kubernetes') {
                        withKubeConfig(caCertificate: '', clusterName: '', contextName: '', credentialsId: 'kubeconfig', namespace: '', restrictKubeConfigAccess: false, serverUrl: '') {
                                sh 'kubectl apply -f deployment.yml'
                                sh 'kubectl apply -f service.yml'
                        }   
                    }
                }
            }
        }


        
         stage('Owasp ZAP - DAST') {
                
                    environment {
                        ZAP_DOCKER_IMAGE = 'ictu/zap2docker-weekly' //this is the image id of owasp/zap2docker-stable, as my linux version is amd64, i'm specifying the already downloaded image
                        ZAP_TARGET = 'http://192.168.100.148:30007'
                        DOCKER_PLATFORM = 'linux/amd64'
                    }
                    steps {
                        script {
                            //  Get the zap docker image and run it in daemon mode, comment down below line if owasp zap image is already available
                            sh "docker pull --platform ${DOCKER_PLATFORM} ${ZAP_DOCKER_IMAGE}"
                            sh "chmod 777 ${pwd}"
                            try {
                                sh "docker run -v ${pwd}:/zap/wrk/:rw -t ${ZAP_DOCKER_IMAGE} zap-baseline.py -t ${ZAP_TARGET} -r zap_report2.html"
                            }
                                catch (Exception e) {
                                                     echo "OWASP ZAP scan failed: ${e}"
                                        // Continue pipeline execution even if OWASP ZAP scan fails
                                            }
                            //below command will remove all the containers build upon owasp image
                            sh "docker ps -a | awk '\$2 ~ /owasp\\/zap2docker-weekly/ {print \$1}' | xargs docker rm" 
                            sh "echo owasp images removed"               
                            sh "mkdir -p owasp-zap-report"
                            sh "sudo mv /var/lib/jenkins/zap_report2.html owasp-zap-report"
                    }
                }
                       post {
                            always {
                                    archiveArtifacts artifacts: 'owasp-zap-report/zap_report2.html'
                                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'owasp-zap-report', reportFiles: 'zap_report2.html', reportName: 'Owasp Zap Report', reportTitles: 'Owasp Zap Report', useWrapperFileDirectly: true])
                                    echo 'Removing container'


                                    cleanWs()                           
                             }
                        }
        
        }

             stage('Kube-bench - CIS benchmarking'){
                steps{
                        sh 'sudo kube-bench run '     
                    }
        }
    }
    
}