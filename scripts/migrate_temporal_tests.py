import os
import re

def update_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    # Pattern for (assoc world :block-time <val>) -> (time.model/with-time world {:block-ts ... :scenario-step ...})
    # This is a bit complex for a simple regex, but we can do a multi-step replacement
    # Or just use a simple regex to replace :block-time <val> with the new format.
    
    # Simple search and replace for the pattern found in many files:
    # :block-time 1000  -> :block-ts (java.time.Instant/ofEpochSecond 1000)
    
    new_content = re.sub(r':block-time\s+(\d+)', 
                         r':block-ts (java.time.Instant/ofEpochSecond \1)', 
                         content)
    
    # We also need to handle cases where :block-time is part of a map definition
    # This might need more sophisticated parsing, but let's try a regex for the most common pattern.
    
    if content != new_content:
        with open(filepath, 'w') as f:
            f.write(new_content)
        print(f"Updated {filepath}")

# Target directory
target_dir = 'test'
for root, dirs, files in os.walk(target_dir):
    for file in files:
        if file.endswith('.clj'):
            update_file(os.path.join(root, file))
