### 准备
本文件旨在讲解 iOS 如何使用 LiveContainer+SideStore 进行自签安装，并达到自动续签、接近巨魔的效果。  
操作需要地定的玩机/动手能力和基础。  
准备工作：一台 iOS 设备（支持到 27 版本），一个外区 Apple 账号，一部 Mac/Windows 电脑，访问外网的工具  
本文参考 Bilibili 视频 ，https://www.bilibili.com/video/BV1TERiBEEg9  

### 资源下载
Windows电脑需下载iTunes（使用官网版而非微软商店版）  
iTunes：https://www.apple.com.cn/itunes  
iLoader：https://www.github.com/nab138/iloader/releases  
localDevVPN（需使用外区商店下载）：https://apps.apple.com/ph/app/localdevvpn/id6755608044   
自动续签快捷指令：https://www.icloud.com/shortcuts/22480eac6bdc410f8e581cfc66eda96e  

### 操作步骤（在Mac演示）
1.打开下载的iLoader并安装，进入主页，在左侧账户页面登录Apple账户，这一步不限制国区/外区账户，但可能要翻墙，规则模式不行的话要换全局模式  
<img width="1470" height="956" alt="image" src="https://github.com/user-attachments/assets/a9b372f8-60c3-488e-ac40-1a52f6cce726" />

2.使用数据线连接iPhone（建议使用线连接，不要使用无线连接），在右侧设备处选中。这一步可能要在手机上点击“信任”或“Trust”。连接时点击一次，选中是还要点击一次。确认选中后设备变成蓝色  
<img width="1066" height="236" alt="截屏2026-08-28 15 24 02" src="https://github.com/user-attachments/assets/7e1c1f48-4e3c-4058-af2b-db2bca1b6b5d" />

3.在这里点击选择版本安装，建议26及以上系统点击第四个“LiveContainer+SideStore每夜版安装”,并等待完成。这一步需连接 Github 下载内容，确保能连接外网    
<img width="1059" height="185" alt="截屏2026-08-28 15 24 20" src="https://github.com/user-attachments/assets/4db83fd1-f069-442e-82bd-4049691709df" />

<img width="1470" height="956" alt="截屏2026-08-28 15 21 20" src="https://github.com/user-attachments/assets/eff6a89f-51a5-4f34-b5dc-f4f2eaa444f4" />

出现以下画面代表安装完成：  
<img width="1178" height="491" alt="截屏2026-08-28 15 22 43" src="https://github.com/user-attachments/assets/88876863-03d6-4856-9798-12e0ee382845" />

4.来到手机操作，确认 LiveContainer 出现在桌面，点击打开提示“不受信任的开发者”。此时进入设置，下拉找到“隐私与安全”，进入后下拉找到“开发者模式”，点击打开，可能要求重启与输入密码

<img width="1501" height="1334" alt="IMG_0548" src="https://github.com/user-attachments/assets/a3de8313-e5e6-48c5-9589-3fcdc03afad5" />
<img width="1501" height="1334" alt="IMG_0549" src="https://github.com/user-attachments/assets/45a535dc-6c4f-42d4-8d40-df79737cf5dc" />

重启手机后，点进设置，进入“通用”，下拉找到“VPN 与设备管理”，点进去找到“开发者 App”。点进去信任。  
<img width="1501" height="1334" alt="IMG_0550" src="https://github.com/user-attachments/assets/8e471690-139b-405b-9249-3e1b600c129f" />

5.打开**梯子全局模式**，打开 LiveContainer，左上角紫色图标进入 SideStore。然后进入“Settings”，点击 Sign in with Apple ID 登录你的账号   
<img width="1364" height="1334" alt="IMG_2014" src="https://github.com/user-attachments/assets/44bd7d03-c8d2-4c5f-90b4-b49f69fa47e6" />

6.登录完成后,**关闭翻墙工具**，打开 LocalDevVPN 连接。再打开 SideStore，选择第四项“MyApps”，在左上角点击加号，选择保存到本地的 ipa 文件安装   
<img width="375" height="667" alt="IMG_0108" src="https://github.com/user-attachments/assets/7da17490-0fc0-433b-88c7-be777aa5c274" />

7.进入快捷指令，确认自动化配置（iOS27 略有区别，需要在快捷指令本身配置。26 系统应该是独立的“自动化标签页配置）
