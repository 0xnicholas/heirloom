# Heirloom Website

Heirloom 产品官网，基于 Vite 8 + React 19 + TypeScript + Tailwind CSS v4 构建。

## 本地开发

```bash
cd website
npm install
npm run dev
```

## 构建

```bash
npm run build
```

构建产物输出到 `dist/` 目录。

## 预览生产构建

```bash
npm run preview
```

## 代码检查

```bash
npm run lint
```

## 项目结构

```
website/
├── index.html              # 入口 HTML
├── vite.config.ts          # Vite 配置（含 @tailwindcss/vite 插件）
├── src/
│   ├── index.css           # Tailwind v4 入口 + 全局样式
│   ├── main.tsx            # React 应用挂载
│   ├── App.tsx             # 页面组合
│   ├── components/         # 可复用组件（Navbar, Footer, Section）
│   └── sections/           # 页面各区块
└── public/
    └── favicon.svg         # 站点图标
```

## 待手动配置

- GitHub 链接已指向 `https://github.com/0xnicholas/heirloom`；如需更改请替换。
- `ResourcesSection` 与 `Footer` 中的 `mailto:hello@heirloom.dev` 可替换为真实联系邮箱。
- 部署时确保 `docs/`、`whitepapers/`、`workshop/` 等静态资源与站点同域可访问。
