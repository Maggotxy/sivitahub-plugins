# AI 素材工作流

该目录通过 OpenAI 兼容 Image API 制作原创 RPG 像素素材。批量生成调用 `/v1/images/generations`，定向修改调用 `/v1/images/edits`；工作区保留提示词、处理脚本、已选用素材的绿幕原稿、去背稿和最终像素图，绝不保存 API 密钥。所有原创源码和素材只留在本地私有工作区，不上传或对外发布。

## 初始化与批量生成

```bash
./resource-pack/ai/setup.sh
./resource-pack/ai/generate.sh --dry-run
./resource-pack/ai/run-with-current-api.sh ./resource-pack/ai/generate.sh --apply
```

`run-with-current-api.sh` 只在执行时读取本机 Codex 配置并导出给子进程，不打印或复制令牌。部署到其他机器时应直接设置 `OPENAI_API_KEY` 和可选的 `OPENAI_BASE_URL`，然后执行 `generate.sh --apply`。网络默认使用 `IMAGE_PROXY=http://127.0.0.1:7890`。

每个素材使用独立提示词和独立请求。`gpt-image-2` 先生成绿幕原稿，之后依次去除背景、裁切主体、压缩到 32×32、映射到服务器统一 24 色调色板，并校验透明角、主体覆盖率和文件尺寸。`--apply` 才会覆盖资源包中的当前纹理、更新实际生产纹理总览图并重建 ZIP。生产包当前采用 28 张内容语义素材和 8 张由可复现像素生成器逐点绘制的旅程 UI 图标，共 36 张；这类需要在 32×32 下保持固定轮廓和一一映射的界面符号不走生图模型。六张旧怪物方块头已经从 `final` 移到 `output/rejected/legacy-head-masks`，不能重新 `--apply`。旅者罗盘的 32 张角度帧由构建脚本本地派生，不额外消耗 Image API 请求。新增资产必须同时加入物品定义、模型、纹理和服务端 `item_model` 引用，不能只生成一张孤立 PNG。

旅者罗盘使用保存在 `prompts/traveler_compass.txt` 的完整提示词，以单次 `/v1/images/generations` 调用生成，避免重跑 26 项批次：

```bash
./resource-pack/ai/run-with-current-api.sh \
  ./resource-pack/ai/.venv/bin/python \
  ~/.codex/skills/.system/imagegen/scripts/image_gen.py generate \
  --model gpt-image-2 \
  --prompt-file ./resource-pack/ai/prompts/traveler_compass.txt \
  --n 1 --size 1024x1024 --quality high --background opaque \
  --output-format png \
  --out ./resource-pack/ai/output/source/traveler_compass.png \
  --force --no-augment
```

只重建当前正式纹理的审核总览图：

```bash
./resource-pack/ai/.venv/bin/python ./resource-pack/ai/contact_sheet.py \
  --textures ./resource-pack/assets/originalrpg/textures/item \
  --out ./resource-pack/ai/output/contact-sheet.png
```

## 单素材编辑

```bash
./resource-pack/ai/run-with-current-api.sh \
  ./resource-pack/ai/edit.sh protection_stone "收紧盾面轮廓，减少高光噪点" --apply
```

编辑使用该素材的 1024×1024 原稿作为输入，走 `/v1/images/edits`，输出带 UTC 版本号的新文件。未传 `--apply` 时只产生候选图，不改变服务器正在使用的资源。
