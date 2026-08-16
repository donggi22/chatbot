from flask import Flask
from server_endpoint import kakao_bp
app = Flask(__name__)
app.register_blueprint(kakao_bp)
app.run(host='0.0.0.0', port=8889)
