# AI 创作引擎
基于 Spring Boot3+ Spring AI Alibaba + 对象存储的智能图文创作平台。用户输入选题后，通过多智能体协作完成文章生成，引入人机协同机制，支持用户在生成过程中参与决策，采用 SSE 流式输出让用户实时看到创作过程。

## 启动后端

```bash
mvn spring-boot:run
```

后端监听端口：8123

接口文档：http://localhost:8123/api/doc.html

## 启动前端

```bash
cd frontend
npm run dev
把前面的去了
```

前端页面：http://localhost:5173
