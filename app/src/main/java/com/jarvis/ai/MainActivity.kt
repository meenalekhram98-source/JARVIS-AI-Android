curl -s -H "Authorization: token $(cat ~/.gh_token)" "https://api.github.com/repos/meenalekhram98-source/JARVIS-AI-Android/actions/runs?per_page=1" | grep -E '"run_number"|"status"|"conclusion"'
