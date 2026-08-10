# pony-v6-img2img-workflow.json — 表情 img2img 工作流模板说明（P-0810-05）

> 用途：同角色表情集生成从「纯文生图」升级为「avatar 文生图 + 表情 img2img」——
> 以角色头像（avatar.png 原图非透明版）为底图做图生图，解决同角色 7 张图脸型漂移问题
> （已在 ComfyUI 实测验证：底图 + denoise 0.45 → 脸型/发型/服装 100% 保持，表情变化正常）。
>
> 说明：JSON 不支持注释，本文件为模板的节点注释（模板文件本身必须保持纯 JSON 可解析）。

## 节点表（与文生图模板 pony-v6-workflow.json 的差异：无 EmptyLatentImage(9)，改 LoadImage+VAEEncode）

| 节点 | class_type | 输入 | 说明 |
|---|---|---|---|
| 1 | CheckpointLoaderSimple | ckpt_name=PonyDiffusionV6XL.safetensors | Pony V6 XL 完整底模（model slot0 / clip slot1 / vae slot2） |
| 5 | LoraLoader | model=[1,0] clip=[1,1] lora_name=__LORA_NAME__ strength 0.8 | 像素风 LoRA（可选；lora 名为空时 ComfyUIClient.applyLoraRewiring 自动改接并移除本节点） |
| 13 | CLIPSetLastLayer | clip=[5,1] stop_at_clip_layer=-2 | Pony 必需 clip skip 2（新版 ComfyUI 0.31 要求） |
| 7 | CLIPTextEncode | clip=[13,0] text=__POSITIVE__ | 正向提示词（score tag + 风格 + 外貌 + 表情） |
| 8 | CLIPTextEncode | clip=[13,0] text=__NEGATIVE__ | 负向提示词（非 NSFW 防线） |
| D | LoadImage | image=__REF_IMAGE__ | 参考图（avatar 底图，经 /upload/image 上传后返回的 input 目录文件名） |
| F | VAEEncode | pixels=[D,0] vae=[1,2] | 参考图编码进潜空间（img2img 的 latent 起点） |
| 10 | KSampler | model=[5,0] positive=[7,0] negative=[8,0] latent_image=[F,0] seed=__SEED__ steps=30 cfg=7.0 dpmpp_2m karras **denoise=__DENOISE__** | 采样；denoise 为 img2img 强度（默认 0.5，可配 roleplay.ai-image.img2img-denoise；0.45 实测脸型/发型/服装全保持） |
| 11 | VAEDecode | samples=[10,0] vae=[1,2] | 潜空间解码回图像 |
| 12 | SaveImage | images=[11,0] filename_prefix=__PREFIX__ | 输出保存（prefix=rp_<角色id>） |

## 占位符清单

`__POSITIVE__ / __NEGATIVE__ / __SEED__ / __LORA_NAME__ / __PREFIX__ / __REF_IMAGE__ / __DENOISE__`

- `__REF_IMAGE__`：/upload/image 上传 avatar.png 后 ComfyUI 返回的 `name`（如 `heroine_avatar_xxxx.png`，input 目录）
- `__DENOISE__`：数值（Double），由 ComfyUIClient.replacePlaceholders 转换（与 seed/宽高同类的数值化处理）
- lora 名为空时 LoraLoader(5) 被移除，引用改接：KSampler.model→[1,0]、CLIPSetLastLayer.clip→[1,1]
