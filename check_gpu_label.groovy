pipeline {
    agent {
        label params.NODE_TO_CHECK ?: 'any'
    }

    parameters {
        string(name: 'NODE_TO_CHECK', defaultValue: '', description: 'Name or label of the node to check')
    }

    stages {
        stage('GPU Label Consistency Check (Multi-GPU)') {
            steps {
                script {
                    // Define multiple email recipients
                    def emailRecipients = 'example@gmail.com' // Add your email addresses here
                    def emailSubject = "GPU Label Check Warnings/Errors on Node ${env.NODE_NAME}"
                    def emailBody = ""

                    try {
                        //////////////////////////////////////////////////////////////////
                        // 1. Basic environment and label checks
                        //////////////////////////////////////////////////////////////////
                        if (!env.NODE_LABELS) {
                            error "ERROR: No NODE_LABELS found. The node might be offline or incorrectly configured."
                        }

                        def labelSet = env.NODE_LABELS.split(/\s+/) as Set
                        if (!labelSet.contains('windows')) {
                            echo "WARNING: Node '${env.NODE_NAME}' does not contain the 'windows' label."
                            emailBody += "WARNING: Node '${env.NODE_NAME}' does not contain the 'windows' label.\n"
                        }

                        //////////////////////////////////////////////////////////////////
                        // 2. Run nvidia-smi to retrieve GPU memory for each GPU
                        //////////////////////////////////////////////////////////////////
                        def smiOutput = ""
                        try {
                            smiOutput = bat(
                                script: '@echo off && nvidia-smi --query-gpu=memory.total --format=csv,noheader',
                                returnStdout: true
                            ).trim()
                        } catch (Exception e) {
                            error "ERROR: Failed to execute 'nvidia-smi' on node '${env.NODE_NAME}'. Ensure NVIDIA drivers are installed and the node is accessible. Details: ${e.message}"
                        }

                        if (!smiOutput) {
                            error "ERROR: No output from 'nvidia-smi'. GPU information could not be retrieved on node '${env.NODE_NAME}'."
                        }

                        // Each line represents the memory of one GPU, e.g.:
                        //  "24576 MiB"
                        //  "24576 MiB"
                        def lines = smiOutput.readLines().collect { it.trim() }

                        //////////////////////////////////////////////////////////////////
                        // 3. Convert each line from "NNNNN MiB" -> integer MiB
                        //////////////////////////////////////////////////////////////////
                        def memoryValuesMiB = []
                        lines.each { line ->
                            try {
                                def numericLine = line.replace(" MiB", "")
                                if (!numericLine.isNumber()) {
                                    error "ERROR: Expected numeric GPU memory, got '${line}'"
                                }
                                memoryValuesMiB << numericLine.toInteger()
                            } catch (Exception e) {
                                error "ERROR: Failed to parse GPU memory value '${line}'. Ensure 'nvidia-smi' output is in the expected format. Details: ${e.message}"
                            }
                        }

                        if (memoryValuesMiB.isEmpty()) {
                            error "ERROR: No valid GPU memory lines found in 'nvidia-smi' output."
                        }

                        //////////////////////////////////////////////////////////////////
                        // 4. Check if all GPUs have the SAME memory
                        //////////////////////////////////////////////////////////////////
                        def uniqueSizes = memoryValuesMiB.toSet() // distinct memory values
                        if (uniqueSizes.size() > 1) {
                            // If GPU memory sizes differ, we mark the build UNSTABLE and log a warning
                            echo "WARNING: Node '${env.NODE_NAME}' has multiple GPUs with different memory sizes: ${uniqueSizes.join(', ')}"
                            emailBody += "WARNING: Node '${env.NODE_NAME}' has multiple GPUs with different memory sizes: ${uniqueSizes.join(', ')}\n"
                            currentBuild.result = 'UNSTABLE'
                        }

                        // We'll assume the memory we care about is from the first GPU
                        def mainMemoryMiB = memoryValuesMiB[0]
                        def memoryInGbDouble = (double) mainMemoryMiB / 1024.0
                        // Round to a long, then cast to int for map lookup
                        def gpuMemoryGB = (int) Math.round(memoryInGbDouble)

                        //////////////////////////////////////////////////////////////////
                        // 5. Mapping from GPU memory (GB) -> label
                        //////////////////////////////////////////////////////////////////
                        def gpuLabelMap = [
                            16: 'gpu_16',
                            24: 'gpu_24',
                            48: 'gpu_48'
                        ]

                        def expectedLabel = gpuLabelMap[gpuMemoryGB]
                        if (!expectedLabel) {
                            echo "WARNING: Node '${env.NODE_NAME}' has ~${gpuMemoryGB}GB GPU (first device), not in gpuLabelMap."
                            emailBody += "WARNING: Node '${env.NODE_NAME}' has ~${gpuMemoryGB}GB GPU (first device), not in gpuLabelMap.\n"
                            currentBuild.result = 'UNSTABLE'
                        } else {
                            //////////////////////////////////////////////////////////////////
                            // 6. Compare the actual GPU label on the node to the expected label
                            //////////////////////////////////////////////////////////////////
                            def currentGpuLabel = labelSet.find { it.startsWith('gpu_') }
                            if (currentGpuLabel && currentGpuLabel != expectedLabel) {
                                echo "=== MISMATCHED GPU LABEL ==="
                                echo "Node: ${env.NODE_NAME}, Expected: ${expectedLabel}, Actual: ${currentGpuLabel}"
                                emailBody += "=== MISMATCHED GPU LABEL ===\n"
                                emailBody += "Node: ${env.NODE_NAME}, Expected: ${expectedLabel}, Actual: ${currentGpuLabel}\n"
                                currentBuild.result = 'UNSTABLE'
                            } else if (!currentGpuLabel) {
                                echo "=== MISSING GPU LABEL ==="
                                echo "Node: ${env.NODE_NAME}, Expected: ${expectedLabel}"
                                emailBody += "=== MISSING GPU LABEL ===\n"
                                emailBody += "Node: ${env.NODE_NAME}, Expected: ${expectedLabel}\n"
                                currentBuild.result = 'UNSTABLE'
                            } else {
                                echo "SUCCESS: Node '${env.NODE_NAME}' has the correct GPU label (${expectedLabel})."
                                if (uniqueSizes.size() == 1 && memoryValuesMiB.size() > 1) {
                                    echo "This node has multiple GPUs (all ${gpuMemoryGB}GB). Label matches that memory."
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Catch any unexpected errors and mark the build as FAILED
                        currentBuild.result = 'FAILURE'
                        echo "ERROR: An unexpected error occurred. Details: ${e.message}"
                        emailBody += "ERROR: An unexpected error occurred. Details: ${e.message}\n"
                    } finally {
                        // Send email if there are warnings, errors, or mismatched labels
                        if (currentBuild.result == 'UNSTABLE' || currentBuild.result == 'FAILURE') {
                            // Add the build URL to the email body
                            emailBody += "\nBuild URL: ${env.BUILD_URL}\n"

                            emailext(
                                subject: emailSubject,
                                body: emailBody,
                                to: emailRecipients // Send to multiple recipients
                            )
                        }
                    }
                }
            }
        }
    }
}
