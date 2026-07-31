# DoVideoAI Web

DoVideoAI 的 Vue 3 前端，包含视频上传、Agent 分析、证据查看与继续追问。

```bash
npm ci
npm run dev
```

开发服务器默认通过 Vite 代理访问 `http://localhost:9090`。后端地址不同时，在项目根目录 `.env` 中设置 `VITE_DEV_PROXY_TARGET`；前后端分开部署时设置 `VITE_API_BASE_URL`。
