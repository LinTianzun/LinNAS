# LinNas 设计系统（Design System）

> 本文件是 `com.linnas.app`（Kotlin 原生 Android，模块 `android/app`）的视觉规范。
> 所有界面统一走这套 tokens，目的是解决“很多界面样式简陋、各自为政”的问题。
>
> **重要前提：本项目没有 Jetpack Compose。** 界面全部是 **XML 布局 + 代码动态生成视图**
> （`LayoutInflater` / `View` 构造 / `LinearLayout.addView`）。因此规范以
> **`res/values/` 资源（dimens / styles / drawable / attrs）** 为唯一落点，
> 不用 Compose 的 `MaterialTheme`、`Modifier` 等概念。新增页面一律按下面的“页面骨架”套用。

---

## 1. 设计 Tokens

### 1.1 颜色（语义层，跨主题不变）

颜色分三层：**语义属性（attr，主题感知）** → **主题色板（`colors.xml`）** → **语义色**。
布局里只允许引用 attr 或语义色，**不要写死色值**。

| Token | 含义 | 定义 |
| --- | --- | --- |
| `?attr/lInk` | 页面底色（最深） | 各主题 ink |
| `?attr/lSurface` | 卡片 / 表面底色 | 各主题 surface |
| `?attr/lSurfaceHigh` | 次级表面（图标底、缩略图占位） | 各主题 surface_high |
| `?attr/lOutline` | 描边 / 分隔线 / 进度轨道 | 各主题 outline |
| `?attr/lText` | 主文本 | 各主题 text |
| `?attr/lTextDim` | 次级 / 辅助文本 | 各主题 text_dim |
| `?attr/lPrimary` | 主题强调色（按钮、图标、进度） | 各主题 primary |
| `?attr/lOnPrimary` | 主强调色上的文字 / 图标 | 各主题 on_primary |
| `?attr/lDanger` | 危险操作（删除、清空、错误标题） | 深色=`danger`，浅色=`danger_light` |
| `?attr/lSuccess` | 成功态 | 深色=`mint`，浅色=`mint_light` |
| `@color/mint` / `@color/amber` | 品牌点缀色（如服务器图标） | `colors.xml` |

主题由 `ThemeStore` 切换，共 6 套：墨蓝 / 墨绿 / 琥珀 / 纯黑（深色）、云白 / 暖沙（浅色）。
`attrs.xml` 的 11 个属性（`lInk/lSurface/lSurfaceHigh/lOutline/lText/lTextDim/lPrimary/lOnPrimary/lDanger/lSuccess/lIsLight`）是 Kotlin 侧 `ThemeColors.color()` 读取的依据，**名称不可改动**。

> 对比度要求：深色主题 `lText`/`lTextDim` 相对 `lInk` 必须满足正文/辅助文本的可读对比；
> 浅色主题（云白 / 暖沙）同理。新增主题时沿用这一约束。

### 1.2 字号与行高（`res/values/dimens.xml`）

统一通过 `@dimen/text_*` 引用，禁止在布局里写裸 `sp`。

| Token | 字号 | 用途 |
| --- | --- | --- |
| `@dimen/text_2xl` | 24sp | 页面大标题（`TextTitle`） |
| `@dimen/text_xl` | 20sp | 分区大标题 / 顶栏标题（`TextHeading`、`HeaderTitle`） |
| `@dimen/text_lg` | 17sp | 子页主标题（`TextSubheading`） |
| `@dimen/text_md` | 15sp | 正文 / 行标题（`TextItem`、`RowTitle`） |
| `@dimen/text_sm` | 13sp | 次要正文 / 说明（`TextCaption`、`EmptyHint`） |
| `@dimen/text_caption` | 12sp | 辅助（`TextMeta`、进度条按钮、卡片状态） |
| `@dimen/text_xs` | 11sp | 极小标签（`TextOverline`、错误堆栈） |

行高：`line_xs` 15dp / `line_sm` 18dp / `line_md` 22dp / `line_lg` 28dp；行内额外间距用 `android:lineSpacingExtra`。

### 1.3 间距（`res/values/dimens.xml`）

节奏为 4dp 的倍数，页面与组件统一使用：

`space_1` 4dp · `space_2` 8dp · `space_3` 12dp · `space_4` 16dp · `space_6` 24dp · `space_8` 32dp

推荐用法：卡片内边距 `card_padding`（16dp）；行之间 `space_2`~`space_3`；区块之间 `space_6`~`space_8`；
页边距统一 16dp（`card_padding`）；标题与正文之间 4~8dp。

### 1.4 圆角（`res/values/dimens.xml`）

| Token | 圆角 | 用途 |
| --- | --- | --- |
| `radius_sm` | 8dp | 小元素 / 标签 |
| `radius_md` | 12dp | 行卡片、图标底、按钮、网格卡（推荐主用） |
| `radius_lg` | 16dp | 大卡片容器（`bg_card`、`CardGroup`） |
| `radius_xl` | 20dp | 大面积容器 / 弹层 |
| `radius_pill` | 999dp | 胶囊、进度条轨道 |

### 1.5 组件尺寸（`res/values/dimens.xml`）

图标：`icon_xs` 16dp / `icon_sm` 18dp / `icon_md` 22dp / `icon_lg` 24dp。
图标底：`tile_sm` 34dp / `tile_md` 42dp / `tile_lg` 44dp。
行最小高度 `row_min_height` 64dp；顶栏 `header_height` 56dp；按钮 `button_height` 44dp；
可点击区域最小 `touch_target` 44dp；描边 `stroke_hairline` 1dp。

### 1.6 层级与动效建议

- **层级**：一律扁平化——`cardElevation=0dp`，用 `lSurface`/`lSurfaceHigh` 的明度差 + `lOutline` 1dp 描边
  区分层级，不做投影。深色下更干净，浅色下避免灰影发脏。
- **动效**：不引入新依赖。按压反馈统一用 `<ripple>`（`bg_ripple.xml` / `bg_button_*.xml`，
  颜色取 `?android:attr/colorControlHighlight`，mask 圆角跟随组件）。
  建议过渡时长 150~250ms、缓动 `ease-out`；主题切换用 `ThemeStore` 的 `recreate()`，
  动画交给系统默认即可，不要自建属性动画框架。

---

## 2. 通用 Drawable（全部为 shape / layer-list，无位图）

命名统一 `bg_*`（背景类）/ `ic_*`（图标类）。`ic_launcher*` 为启动图标，**不得改动**。

| 资源 | 类型 | 用途 |
| --- | --- | --- |
| `bg_card.xml` | shape | 圆角卡片底：`lSurface` + `radius_lg` + 1dp `lOutline`，主题感知 |
| `bg_icon_circle.xml` | shape(oval) | 圆形图标底，`lSurfaceHigh` |
| `bg_tile.xml` | shape | 方形图标底，`lSurfaceHigh` + `radius_md` |
| `bg_pill.xml` | shape | 胶囊 / 面包屑底，`radius_pill` |
| `bg_ripple.xml` | ripple | 圆角按下涟漪（透明底，可作 `foreground`） |
| `bg_button_primary.xml` | ripple | 主要按钮底：`lPrimary` 填充 + 涟漪 |
| `bg_button_tonal.xml` | ripple | 次级按钮底：`lSurfaceHigh` + `lOutline` 描边 + 涟漪 |
| `bg_selected.xml` | shape | 选中态高亮：`lSurfaceHigh` + `lPrimary` 描边 |
| `bg_divider.xml` | shape | 1dp 分隔线，`lOutline`（配合 `@style/Divider`） |
| `bg_dot.xml` | shape(oval) | 分区标题前的小圆点（8dp，`lPrimary`） |
| `bg_empty.xml` | layer-list | 空状态同心圆占位（纯矢量，无位图） |

---

## 3. 组件与样式（`res/values/themes.xml` + `res/values/styles.xml`）

### 3.1 既有样式（保留名称，仅增强，勿改名/删除）

`TextTitle`、`TextLabel`、`TextItem`、`TextMeta`、`TextSection`、`CardGroup`、
`RowItem`、`RowIcon`、`RowTitle`、`RowValue`、`RowDivider`、`RowChevron`、
`PageHeader`、`HeaderIconButton`、`HeaderTitle`、`HeaderTextAction`、`ChipButton`、
`EmptyTitle`、`EmptyHint`。Kotlin 与 `activity_main.xml` / `activity_vault.xml` 有引用，**改名即编译失败**。

### 3.2 新增样式（`styles.xml`）

排版：`TextDisplay`（24sp 大标题）、`TextHeading`（20sp）、`TextSubheading`（17sp）、
`TextBody`（15sp）、`TextCaption`（13sp）、`TextOverline`（11sp）、`SectionHeader`（14sp 分区标题）。
容器/组件：`CardContainer`（`bg_card` + `card_padding` + 底部间距）、`Divider`（1dp 分隔线）、
`IconCircle`（42dp 圆形图标底）、`BtnPrimary`（主按钮）、`BtnTonal`（次级按钮）。

### 3.3 组件使用示例

**圆角行（文件 / 服务器 / 分享条目）** —— `bg_card` 打底 + `bg_ripple` 做按压：

```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:background="@drawable/bg_card"
    android:foreground="@drawable/bg_ripple"
    android:paddingStart="@dimen/card_padding"
    android:paddingEnd="@dimen/card_padding"
    android:layout_marginBottom="@dimen/space_3">
    <!-- 圆形图标底 -->
    <ImageView style="@style/IconCircle"
        android:src="@drawable/ic_wifi" android:tint="@color/mint"
        android:contentDescription="服务器" />
    <!-- 标题 + 辅助文本 -->
    <TextView android:id="@+id/srvName" style="@style/TextItem" ... />
    <TextView android:id="@+id/srvMeta" style="@style/TextMeta" ... />
</LinearLayout>
```

**媒体网格卡** —— 用 `MaterialCardView` + `radius_md`：

```xml
<com.google.android.material.card.MaterialCardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_margin="@dimen/space_2"
    app:cardBackgroundColor="?attr/lSurface"
    app:cardCornerRadius="@dimen/radius_md"
    app:strokeColor="?attr/lOutline"
    app:strokeWidth="@dimen/stroke_hairline">
    ...
</com.google.android.material.card.MaterialCardView>
```

**子页顶栏** —— 返回/操作用文字按钮，标题用 `text_lg`，下接一条分隔线：

```xml
<LinearLayout ... android:paddingStart="@dimen/space_2" android:paddingEnd="@dimen/space_2"
    android:paddingTop="@dimen/space_1" android:paddingBottom="@dimen/space_1">
    <Button android:id="@+id/btnXxxBack" android:text="返回"
        android:textColor="?attr/lPrimary" android:textSize="@dimen/text_sm"
        android:background="?attr/selectableItemBackgroundBorderless" ... />
    <TextView android:layout_weight="1" android:text="标题"
        android:textSize="@dimen/text_lg" android:textStyle="bold"
        android:textColor="?attr/lText" ... />
    <Button android:id="@+id/btnXxxAction" android:text="操作" ... />
</LinearLayout>
<View style="@style/Divider"
    android:layout_marginStart="@dimen/card_padding"
    android:layout_marginEnd="@dimen/card_padding" />
```

**空状态** —— `bg_empty` 作插图占位，配 `EmptyTitle` / `EmptyHint`：

```xml
<ImageView android:src="@drawable/bg_empty" android:contentDescription="暂无数据" ... />
<TextView style="@style/EmptyTitle" android:text="还没有内容" />
<TextView style="@style/EmptyHint" android:text="做点什么来填充这里" />
```

---

## 4. 页面骨架规范

所有新页面遵循统一骨架（自上而下）：

1. **根容器**：`LinearLayout`（vertical），`android:background="?attr/lInk"`，
   若整页留白则用 `android:padding="@dimen/space_6"`，否则内部逐块加边距。
2. **顶栏**：高度约 `header_height`（56dp），左右各留 `space_2`；
   左侧返回（文字按钮 / `HeaderIconButton`）、中间标题（`text_lg` 加粗、单行省略）、
   右侧主操作（文字按钮或 `MaterialButton`）。顶栏与正文之间放一条 `@style/Divider`。
3. **说明区**：`text_caption` + `lTextDim`，`paddingStart/End = card_padding`，
   与顶栏之间 `space_3`，与列表之间 `space_2`。
4. **内容区**：`ScrollView`（`layout_weight="1"`、`scrollbars="none"`）包列表容器；
   列表容器 `paddingStart/End = card_padding`，底部留 `space_4`。
5. **行 / 卡片**：列表项用 `bg_card` + 圆角图标底 + 文本；间距用 `space_2`~`space_3`；
   可点击行叠加 `bg_ripple` 做按压反馈。
6. **底部操作**：主操作 `@style/BtnPrimary`，次操作 `@style/BtnTonal`，用 `space_3` 分隔。

---

## 5. 落地步骤（新页面怎么套用）

1. 在 `res/layout/` 新建 XML 布局，引用上面的 tokens（`@dimen/*`、`@style/*`、`@drawable/bg_*`）。
2. 只写视觉属性，`android:id` 与既有命名保持一致，避免与 Kotlin 的 `findViewById` 冲突。
3. 颜色一律用 `?attr/l*`，**不要写死 `#RRGGBB`**（崩溃页等已从硬编码改为语义属性）。
4. 需要新图标时用 vector（`res/drawable/ic_*.xml`），不要引入位图。
5. 需要新尺寸/样式时**只新增 token 或样式**，不改动既有 `@style` / `attr` 名称。
6. 代码动态生成的视图（如 `MainActivity` 里的行、面包屑 chip）同样读取
   `ThemeColors.color(ctx, R.attr.lPrimary)` 等，确保与 XML 同源。

> 校验：改动后由 Lead 统一执行构建（本设计任务不运行 Gradle）。所有 XML 需标签闭合、
> 属性合法、引用资源存在；`android:id` 集合不变、主题 attr 未删。
