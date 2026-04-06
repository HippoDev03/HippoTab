pipeline {
    agent any

    tools {
        jdk 'jdk21'
    }

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    environment {
        GRADLE_USER_HOME = "${WORKSPACE}/.gradle"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh './gradlew --no-daemon clean jar'
                sh '''
                    set -eu
                    jar_file="$(find build/libs -maxdepth 1 -type f -name '*.jar' | head -n 1)"
                    if [ -z "$jar_file" ]; then
                      echo "No jar found in build/libs"
                      exit 1
                    fi
                    jar_dir="$(dirname "$jar_file")"
                    jar_name="$(basename "$jar_file" .jar)"
                    mv "$jar_file" "${jar_dir}/${jar_name}-${BUILD_NUMBER}.jar"
                '''
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true
            junit testResults: 'build/test-results/test/*.xml', allowEmptyResults: true
        }
    }
}