import subprocess
import smtplib
from email.mime.text import MIMEText
import os

# Configuration
EMAIL_RECIPIENTS = "mygmail@gmail.com"
EMAIL_SUBJECT = "GPU Label Check Warnings/Errors"
NODE_NAME = os.getenv("COMPUTERNAME", "unknown-node")  # Get the node name from environment variables

# GPU memory to label mapping
GPU_LABEL_MAP = {
    16: 'gpu_16',
    24: 'gpu_24',
    48: 'gpu_48'
}

def send_email(subject, body, recipients):
    """
    Send an email using SMTP.
    """
    sender = "jenkins@example.com"  # Replace with your sender email
    msg = MIMEText(body)
    msg["Subject"] = subject
    msg["From"] = sender
    msg["To"] = recipients

    try:
        with smtplib.SMTP("smtp.example.com") as server:  # Replace with your SMTP server
            server.sendmail(sender, recipients.split(", "), msg.as_string())
        print("Email sent successfully.")
    except Exception as e:
        print(f"Failed to send email: {e}")

def run_command(command):
    """
    Run a shell command and return the output.
    """
    try:
        result = subprocess.run(command, shell=True, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        return result.stdout.strip()
    except subprocess.CalledProcessError as e:
        raise Exception(f"Command failed: {e.stderr}")

def get_gpu_memory():
    """
    Retrieve GPU memory information using nvidia-smi.
    """
    try:
        smi_output = run_command("nvidia-smi --query-gpu=memory.total --format=csv,noheader")
        if not smi_output:
            raise Exception("No output from 'nvidia-smi'. GPU information could not be retrieved.")
        return smi_output.splitlines()
    except Exception as e:
        raise Exception(f"Failed to execute 'nvidia-smi': {e}")

def parse_gpu_memory(lines):
    """
    Parse GPU memory values from nvidia-smi output.
    """
    memory_values_mib = []
    for line in lines:
        try:
            numeric_line = line.replace(" MiB", "")
            if not numeric_line.isdigit():
                raise Exception(f"Expected numeric GPU memory, got '{line}'")
            memory_values_mib.append(int(numeric_line))
        except Exception as e:
            raise Exception(f"Failed to parse GPU memory value '{line}': {e}")
    
    if not memory_values_mib:
        raise Exception("No valid GPU memory lines found in 'nvidia-smi' output.")
    
    return memory_values_mib

def check_gpu_labels(memory_values_mib, label_set):
    """
    Check GPU labels and memory consistency.
    """
    email_body = ""
    unique_sizes = set(memory_values_mib)

    # Check if all GPUs have the same memory
    if len(unique_sizes) > 1:
        warning = f"WARNING: Node '{NODE_NAME}' has multiple GPUs with different memory sizes: {', '.join(map(str, unique_sizes))}"
        print(warning)
        email_body += warning + "\n"

    # Assume the memory we care about is from the first GPU
    main_memory_mib = memory_values_mib[0]
    gpu_memory_gb = int(round(main_memory_mib / 1024.0))

    # Check if the GPU memory is in the label map
    expected_label = GPU_LABEL_MAP.get(gpu_memory_gb)
    if not expected_label:
        warning = f"WARNING: Node '{NODE_NAME}' has ~{gpu_memory_gb}GB GPU (first device), not in GPU label map."
        print(warning)
        email_body += warning + "\n"
    else:
        # Compare the actual GPU label on the node to the expected label
        current_gpu_label = next((label for label in label_set if label.startswith("gpu_")), None)
        if current_gpu_label and current_gpu_label != expected_label:
            mismatch = f"=== MISMATCHED GPU LABEL ===\nNode: {NODE_NAME}, Expected: {expected_label}, Actual: {current_gpu_label}"
            print(mismatch)
            email_body += mismatch + "\n"
        elif not current_gpu_label:
            missing = f"=== MISSING GPU LABEL ===\nNode: {NODE_NAME}, Expected: {expected_label}"
            print(missing)
            email_body += missing + "\n"
        else:
            success = f"SUCCESS: Node '{NODE_NAME}' has the correct GPU label ({expected_label})."
            if len(unique_sizes) == 1 and len(memory_values_mib) > 1:
                success += f"\nThis node has multiple GPUs (all {gpu_memory_gb}GB). Label matches that memory."
            print(success)

    return email_body

def main():
    email_body = ""

    try:
        # 1. Basic environment and label checks
        node_labels = os.getenv("NODE_LABELS", "")
        if not node_labels:
            raise Exception("ERROR: No NODE_LABELS found. The node might be offline or incorrectly configured.")

        label_set = set(node_labels.split())
        if "windows" not in label_set:
            warning = f"WARNING: Node '{NODE_NAME}' does not contain the 'windows' label."
            print(warning)
            email_body += warning + "\n"

        # 2. Retrieve GPU memory information
        smi_lines = get_gpu_memory()

        # 3. Parse GPU memory values
        memory_values_mib = parse_gpu_memory(smi_lines)

        # 4. Check GPU labels and memory consistency
        email_body += check_gpu_labels(memory_values_mib, label_set)

    except Exception as e:
        error = f"ERROR: An unexpected error occurred. Details: {e}"
        print(error)
        email_body += error + "\n"
        email_subject = f"GPU Label Check FAILED on Node {NODE_NAME}"
    else:
        email_subject = f"GPU Label Check Warnings on Node {NODE_NAME}"

    # 5. Send email if there are warnings or errors
    if email_body:
        email_body += f"\nBuild URL: {os.getenv('BUILD_URL', 'Not available')}\n"
        send_email(EMAIL_SUBJECT, email_body, EMAIL_RECIPIENTS)

if __name__ == "__main__":
    main()
