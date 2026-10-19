using System.Text;

namespace GrimmConvert;

/// <summary>Decodes the uncompressed and DXT Unity texture formats and writes PNG files. No image library needed.</summary>
static class Textures
{
    // Unity TextureFormat values.
    public const int RGB24 = 3, RGBA32 = 4, ARGB32 = 5, DXT1 = 10, DXT5 = 12, DXT1Crunched = 28, DXT5Crunched = 29;

    public static string FormatName(int fmt) => fmt switch
    {
        RGB24 => "RGB24", RGBA32 => "RGBA32", ARGB32 => "ARGB32", DXT1 => "DXT1", DXT5 => "DXT5",
        DXT1Crunched => "DXT1 Crunched", DXT5Crunched => "DXT5 Crunched", _ => "format " + fmt,
    };

    public static bool CanDecode(int fmt) => fmt is RGB24 or RGBA32 or ARGB32 or DXT1 or DXT5;

    /// <summary>Returns top-down RGBA8 (Unity stores textures bottom-up), or null when the format is not supported.</summary>
    public static byte[] Decode(byte[] d, int w, int h, int fmt)
    {
        var o = new byte[w * h * 4];
        switch (fmt)
        {
            case RGBA32:
                for (int y = 0; y < h; y++) Array.Copy(d, y * w * 4, o, (h - 1 - y) * w * 4, w * 4);
                return o;
            case RGB24:
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                    {
                        int s = (y * w + x) * 3, t = ((h - 1 - y) * w + x) * 4;
                        o[t] = d[s]; o[t + 1] = d[s + 1]; o[t + 2] = d[s + 2]; o[t + 3] = 255;
                    }
                return o;
            case ARGB32:
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                    {
                        int s = (y * w + x) * 4, t = ((h - 1 - y) * w + x) * 4;
                        o[t] = d[s + 1]; o[t + 1] = d[s + 2]; o[t + 2] = d[s + 3]; o[t + 3] = d[s];
                    }
                return o;
            case DXT1:
            case DXT5:
                DecodeDxt(d, w, h, fmt == DXT5, o);
                return o;
        }
        return null;
    }

    static void DecodeDxt(byte[] d, int w, int h, bool dxt5, byte[] o)
    {
        int bw = (w + 3) / 4, bh = (h + 3) / 4, blockSize = dxt5 ? 16 : 8, p = 0;
        var alpha = new byte[16];
        for (int by = 0; by < bh; by++)
            for (int bx = 0; bx < bw; bx++)
            {
                Array.Fill(alpha, (byte)255);
                int cp = p;
                if (dxt5)
                {
                    int a0 = d[p], a1 = d[p + 1];
                    var at = new int[8]; at[0] = a0; at[1] = a1;
                    if (a0 > a1) for (int i = 1; i < 7; i++) at[i + 1] = ((7 - i) * a0 + i * a1) / 7;
                    else { for (int i = 1; i < 5; i++) at[i + 1] = ((5 - i) * a0 + i * a1) / 5; at[6] = 0; at[7] = 255; }
                    ulong bits = 0;
                    for (int i = 0; i < 6; i++) bits |= (ulong)d[p + 2 + i] << (8 * i);
                    for (int i = 0; i < 16; i++) alpha[i] = (byte)at[(bits >> (3 * i)) & 7];
                    cp = p + 8;
                }
                int c0 = d[cp] | d[cp + 1] << 8, c1 = d[cp + 2] | d[cp + 3] << 8;
                var col = new int[4][];
                col[0] = Rgb565(c0); col[1] = Rgb565(c1);
                bool fourColor = c0 > c1 || dxt5;
                if (fourColor)
                {
                    col[2] = new[] { (2 * col[0][0] + col[1][0]) / 3, (2 * col[0][1] + col[1][1]) / 3, (2 * col[0][2] + col[1][2]) / 3 };
                    col[3] = new[] { (col[0][0] + 2 * col[1][0]) / 3, (col[0][1] + 2 * col[1][1]) / 3, (col[0][2] + 2 * col[1][2]) / 3 };
                }
                else
                {
                    col[2] = new[] { (col[0][0] + col[1][0]) / 2, (col[0][1] + col[1][1]) / 2, (col[0][2] + col[1][2]) / 2 };
                    col[3] = new[] { 0, 0, 0 };
                }
                uint idx = (uint)(d[cp + 4] | d[cp + 5] << 8 | d[cp + 6] << 16 | d[cp + 7] << 24);
                for (int i = 0; i < 16; i++)
                {
                    int px = bx * 4 + (i & 3), py = by * 4 + (i >> 2);
                    if (px >= w || py >= h) continue;
                    var sel = (int)((idx >> (2 * i)) & 3);
                    var c = col[sel];
                    int t = ((h - 1 - py) * w + px) * 4;
                    o[t] = (byte)c[0]; o[t + 1] = (byte)c[1]; o[t + 2] = (byte)c[2];
                    o[t + 3] = dxt5 ? alpha[i] : (byte)(!fourColor && sel == 3 ? 0 : 255);
                }
                p += blockSize;
            }
    }

    static int[] Rgb565(int c) => new[] { ((c >> 11) & 31) * 255 / 31, ((c >> 5) & 63) * 255 / 63, (c & 31) * 255 / 31 };

    static readonly uint[] CrcTable = BuildCrcTable();

    static uint[] BuildCrcTable()
    {
        var t = new uint[256];
        for (uint n = 0; n < 256; n++)
        {
            uint c = n;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1;
            t[n] = c;
        }
        return t;
    }

    static uint Crc(byte[] b)
    {
        uint c = 0xFFFFFFFF;
        foreach (var x in b) c = CrcTable[(c ^ x) & 0xFF] ^ (c >> 8);
        return c ^ 0xFFFFFFFF;
    }

    public static void WritePng(string path, byte[] rgba, int w, int h)
    {
        using var fs = File.Create(path);
        fs.Write(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 });

        void Chunk(string type, byte[] body)
        {
            var tb = Encoding.ASCII.GetBytes(type);
            var len = BitConverter.GetBytes((uint)body.Length); Array.Reverse(len); fs.Write(len);
            var all = tb.Concat(body).ToArray(); fs.Write(all);
            var crc = BitConverter.GetBytes(Crc(all)); Array.Reverse(crc); fs.Write(crc);
        }

        var ihdr = new byte[13];
        var wb = BitConverter.GetBytes((uint)w); Array.Reverse(wb);
        var hb = BitConverter.GetBytes((uint)h); Array.Reverse(hb);
        wb.CopyTo(ihdr, 0); hb.CopyTo(ihdr, 4); ihdr[8] = 8; ihdr[9] = 6;
        Chunk("IHDR", ihdr);
        var raw = new byte[(w * 4 + 1) * h];
        for (int y = 0; y < h; y++) Array.Copy(rgba, y * w * 4, raw, y * (w * 4 + 1) + 1, w * 4);
        using var ms = new MemoryStream();
        using (var z = new System.IO.Compression.ZLibStream(ms, System.IO.Compression.CompressionLevel.Fastest, true)) z.Write(raw, 0, raw.Length);
        Chunk("IDAT", ms.ToArray());
        Chunk("IEND", Array.Empty<byte>());
    }
}
