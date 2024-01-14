
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

        stage("quality gate") {
            steps {
                script {
                    waitForQualityGate abortPipeline: false, credentialsId: 'Sonar-token'
                }
            }
        }
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

        stage('OPA Conftest - Docker File Scan')
            steps {
                sh 'conftest test --policy Opa-Docker-Security.rego Dockerfile'
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
        stage("TRIVY"){
            steps{
                sh "trivy image khizarsheraz/netflix:latest > trivyimage.txt" 
            }
        }
        stage('Deploy to container'){
            steps{
                sh 'docker run -d -p 8081:80 khizarsheraz/netflix:latest'
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
    }
}