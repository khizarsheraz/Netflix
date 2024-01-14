pipeline {
                agent any
                stages {
                    stage('Build Artifact - Maven') {
                        steps {
                            sh "mvn clean package -DskipTests=true"
                            sh "hostname"
                            archive 'target/*.jar'
                        }
                        }

                    stage('Unit Tests - JUnit and JaCoCo') {
                        steps {
                            sh "mvn test"
                        }
                        post { 
                        always { 
                          junit 'target/surefire-reports/*.xml'
                          jacoco execPattern: 'target/jacoco.exec'
                        }
                        }
                    }

                    stage('Docker Build and Push') {
                        steps {
                            withDockerRegistry([credentialsId: "dockerhub", url: ""]) {
                            sh 'printenv'
                            sh 'sudo docker build -t khizarsheraz/devsecops:""$GIT_COMMIT"" .'
                            sh 'docker push khizarsheraz/devsecops:""$GIT_COMMIT""'
                            } 
                        }
                    }

                    stage("kUBERNETES DEPLOYMENT - dEV"){
                        steps{
                            withKubeConfig([credentialsId: 'kubeconfig']){
                            sh "sed -i 's#replace#khizarsheraz/devsecops:${GIT_COMMIT}#g' k8s_deployment_service.yaml" 
                            sh "kubectl apply -f k8s_deployment_service.yaml"

                            }
                        }
                    }
                        
                }        
        } 


