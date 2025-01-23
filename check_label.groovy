pipeline {
    agent any  // Or agent { label "some_label" }

    stages {
        stage('Check Node Labels vs HW') {
            steps {
                script {
                    // ------------------------------------
                    // Retrieve the labels assigned to the current node
                    // ------------------------------------
                    def nodeLabels = env.NODE_LABELS?.split() ?: []
                    println "Current node labels: ${nodeLabels}"

                    // ------------------------------------
                    // GPU Memory Check
                    // ------------------------------------
                    def gpuCheckPassed = true
                    // Check if nvidia-smi is available on this node
                    def nvidiaSmiInstalled = (sh script: "nvidia-smi", returnStatus: true) == 0

                    // Only proceed if any label starts with "gpu_"
                    if (nodeLabels.any { it.startsWith('gpu_') }) {
                        if (!nvidiaSmiInstalled) {
                            echo "WARNING: Node is labeled with GPU but 'nvidia-smi' is not installed or not in PATH."
                            gpuCheckPassed = false
                        } else {
                            // If multiple GPUs, we're just checking the first GPU here.
                            def rawMem = sh(
                                script: """
                                    nvidia-smi --query-gpu=memory.total --format=csv,noheader | head -n1
                                """,
                                returnStdout: true
                            ).trim()

                            def gpuMemMB = rawMem.isInteger() ? rawMem.toInteger() : 0
                            echo "Detected GPU Memory (first GPU): ${gpuMemMB} MB (~${(gpuMemMB / 1024).round(1)} GB)"

                            // Compare with expected memory for each GPU label
                            if (nodeLabels.contains("gpu_16") && gpuMemMB < 16000) {
                                echo "WARNING: Node labeled 'gpu_16' but only has ~${(gpuMemMB / 1024).round(1)} GB."
                                gpuCheckPassed = false
                            }
                            if (nodeLabels.contains("gpu_24") && gpuMemMB < 24000) {
                                echo "WARNING: Node labeled 'gpu_24' but only has ~${(gpuMemMB / 1024).round(1)} GB."
                                gpuCheckPassed = false
                            }
                            if (nodeLabels.contains("gpu_48") && gpuMemMB < 48000) {
                                echo "WARNING: Node labeled 'gpu_48' but only has ~${(gpuMemMB / 1024).round(1)} GB."
                                gpuCheckPassed = false
                            }
                        }
                    }

                    // ------------------------------------
                    // RAM Check (only if "ram_high" label is present)
                    // ------------------------------------
                    def ramCheckPassed = true
                    // Check total system RAM in MB
                    def rawSystemMem = sh(
                        script: "free -m | grep Mem | awk '{print \$2}'",
                        returnStdout: true
                    ).trim()

                    def systemMemMB = rawSystemMem.isInteger() ? rawSystemMem.toInteger() : 0
                    echo "Detected System Memory: ${systemMemMB} MB (~${(systemMemMB / 1024).round(1)} GB)"

                    if (nodeLabels.contains("ram_high")) {
                        // Must have >= 32GB if labeled ram_high
                        if (systemMemMB < 32000) {
                            echo "WARNING: Node labeled 'ram_high' but has ~${(systemMemMB / 1024).round(1)} GB (<32GB)."
                            ramCheckPassed = false
                        }
                    }

                    // ------------------------------------
                    // Final results or decisions
                    // ------------------------------------
                    if (!gpuCheckPassed || !ramCheckPassed) {
                        echo "One or more hardware-label mismatches detected. Marking build as UNSTABLE."
                        currentBuild.result = 'UNSTABLE'
                    }

                    // If you want to fail the build outright on mismatch, uncomment the following:
                    /*
                    if (!gpuCheckPassed || !ramCheckPassed) {
                        error("Hardware mismatch detected on labels. Failing the build.")
                    }
                    */
                }
            }
        }
    }
}
