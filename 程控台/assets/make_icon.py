"""生成程序图标。运行需要 Pillow，正式使用程控台不需要。"""

from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent


def draw(scale: int = 16) -> Image.Image:
    size = 64 * scale
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    pen = ImageDraw.Draw(image)

    def block(x, y, width, height, radius, fill):
        pen.rounded_rectangle(
            [x * scale, y * scale, (x + width) * scale - 1, (y + height) * scale - 1],
            radius=radius * scale,
            fill=fill,
        )

    block(4, 4, 56, 56, 14, (36, 52, 62, 255))
    cell = (228, 238, 234, 255)
    brass = (198, 161, 91, 255)
    block(14, 14, 15, 15, 3.5, cell)
    block(35, 14, 15, 15, 3.5, cell)
    block(14, 35, 15, 15, 3.5, cell)
    block(35, 35, 15, 15, 3.5, brass)
    return image


def main() -> None:
    master = draw(16)
    png = master.resize((256, 256), Image.Resampling.LANCZOS)
    png.save(HERE / "icon.png")
    png.save(
        HERE / "icon.ico",
        format="ICO",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (256, 256)],
    )


if __name__ == "__main__":
    main()
