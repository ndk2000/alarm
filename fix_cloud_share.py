import sys

path = r'D:\ai\droidcloud-ide\app\src\main\java\com\example\ui\screens\CloudShareTab.kt'

with open(path, 'rb') as f:
    data = f.read()

# Split by \r\n first, then \n fallback
if b'\r\n' in data:
    lines = data.split(b'\r\n')
    newline = b'\r\n'
else:
    lines = data.split(b'\n')
    newline = b'\n'

# Keep only first 935 lines (L935 = index 934)
# L935 is "    }" which closes deleteConfirmCode?.let block
# We need to add: } (close else) + } (close ManagePanel)
result = newline.join(lines[:935])
if not result.endswith(newline):
    result += newline
result += b'}' + newline
result += b'}' + newline

with open(path, 'wb') as f:
    f.write(result)

with open(path, 'r', encoding='utf-8') as f:
    all_lines = f.readlines()

# Write debug info
with open(r'D:\ai\droidcloud-ide\fix_result.txt', 'w', encoding='utf-8') as f:
    f.write(f'Total lines: {len(all_lines)}\n')
    f.write(f'Last 5 lines:\n')
    for i, line in enumerate(all_lines[-5:], len(all_lines)-4):
        f.write(f'  L{i}: {repr(line)}\n')

print(f'Done. Total lines: {len(all_lines)}')
