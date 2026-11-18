# PoseVision

YOLO11n-pose + ncnn 的独立人体检测 App。画面叠「角标检测框 + 完整骨架」，无闪烁。

## 特点

- 检测框：细边 + 四角加粗，每人一种颜色
- 骨架：COCO 17 点，连线 + 关节点
- 防闪：结果平滑插值 + 丢失保留 260ms
- 运行日志：主页内嵌预览 + 独立日志页（可清空、可选中复制）

## 结构

```
MainActivity     主页（背景图 + 状态 + 日志预览）
LogActivity      全屏日志
CameraService    前台服务，CameraX 取帧 → 推理 → 更新悬浮层
OverlayView      悬浮绘制：方框 + 骨架 + HUD
PoseEngine       ncnn 推理封装（JNI）
RunLog           环形日志缓冲
```

## 权限

相机、通知、**显示在其他应用上层**（悬浮窗必需）
