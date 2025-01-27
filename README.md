This Jenkins Pipeline checks a Windows node’s NVIDIA GPUs for correct labeling. It queries nvidia-smi for each GPU’s memory, 
ensures they match (if multiple), and compares the total (or first) memory size against a predefined label map (e.g., gpu_16, gpu_24, gpu_48). 
If labels are missing or mismatched, it marks the job as UNSTABLE, helping you maintain accurate GPU labels per node
