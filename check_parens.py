import sys

def check_parens(filename):
    with open(filename, 'r') as f:
        content = f.read()

    stack = []
    for i, char in enumerate(content):
        if char in '([{':
            stack.append((char, i))
        elif char in ')]}':
            if not stack:
                print(f"Unmatched closing char {char} at index {i}")
                # Print context
                start = max(0, i - 40)
                end = min(len(content), i + 40)
                print(f"Context: ...{content[start:i]}>>> {char} <<<{content[i+1:end]}...")
                return False
            opening, pos = stack.pop()
            if (opening == '(' and char != ')') or \
               (opening == '[' and char != ']') or \
               (opening == '{' and char != '}'):
                print(f"Mismatched chars: {opening} at {pos} and {char} at {i}")
                # Print context
                start = max(0, i - 40)
                end = min(len(content), i + 40)
                print(f"Context: ...{content[start:i]}>>> {char} <<<{content[i+1:end]}...")
                return False
    
    if stack:
        for char, pos in stack:
            print(f"Unmatched opening char {char} at index {pos}")
            # Print context
            start = max(0, pos - 40)
            end = min(len(content), pos + 40)
            print(f"Context: ...{content[start:pos]}>>> {char} <<<{content[pos+1:end]}...")
        return False
    
    print("All parens matched!")
    return True

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 check_parens.py <filename>")
    else:
        check_parens(sys.argv[1])
