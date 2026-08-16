kill $(lsof -ti:8889)
cd /home/dev/chatbot && nohup /home/dev/chatbot/venv/bin/python3 -c "
from flask import Flask
from server_endpoint import kakao_bp
app = Flask(__name__)
app.register_blueprint(kakao_bp)
app.run(host='0.0.0.0', port=8889)
" >> /home/dev/chatbot/server.log 2>&1 &
echo $! > /home/dev/chatbot/server.pid
