package com.myonlinetime.app.utils;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.IOException;
import java.io.OutputStream;

public class AnimatedGifEncoder {
    protected int width;
    protected int height;
    protected int transparent = Color.TRANSPARENT;
    protected int transIndex;
    protected int repeat = -1;
    protected int delay = 0;
    protected boolean started = false;
    protected OutputStream out;
    protected Bitmap image;
    protected byte[] pixels;
    protected byte[] indexedPixels;
    protected int colorDepth;
    protected byte[] colorTab;
    protected boolean[] usedEntry = new boolean[256];
    protected int palSize = 7;
    protected int dispose = -1;
    protected boolean closeStream = false;
    protected boolean firstFrame = true;
    protected boolean sizeSet = false;
    protected int sample = 10;

    public void setDelay(int ms) { delay = Math.round(ms / 10.0f); }
    public void setDispose(int code) { if (code >= 0) dispose = code; }
    public void setRepeat(int iter) { if (iter >= 0) repeat = iter; }
    public void setTransparent(int c) { transparent = c; }
    public void setQuality(int q) { if (q < 1) q = 1; sample = q; }

    public boolean addFrame(Bitmap im) {
        if ((im == null) || !started) return false;
        boolean ok = true;
        try {
            if (!sizeSet) setSize(im.getWidth(), im.getHeight());
            image = im;
            getImagePixels();
            analyzePixels();
            if (firstFrame) {
                writeLSD();
                writePalette();
                if (repeat >= 0) writeNetscapeExt();
            }
            writeGraphicCtrlExt();
            writeImageDesc();
            if (!firstFrame) writePalette();
            writePixels();
            firstFrame = false;
        } catch (IOException e) { ok = false; }
        return ok;
    }

    public boolean finish() {
        if (!started) return false;
        boolean ok = true;
        started = false;
        try {
            out.write(0x3b);
            out.flush();
            if (closeStream) out.close();
        } catch (IOException e) { ok = false; }
        transIndex = 0;
        out = null;
        image = null;
        pixels = null;
        indexedPixels = null;
        colorTab = null;
        closeStream = false;
        firstFrame = true;
        return ok;
    }

    public void setSize(int w, int h) {
        if (started && !firstFrame) return;
        width = w;
        height = h;
        if (width < 1) width = 320;
        if (height < 1) height = 240;
        sizeSet = true;
    }

    public boolean start(OutputStream os) {
        if (os == null) return false;
        boolean ok = true;
        closeStream = false;
        out = os;
        try {
            writeString("GIF89a");
        } catch (IOException e) { ok = false; }
        return started = ok;
    }

    protected void analyzePixels() {
        int len = pixels.length;
        int nPix = len / 3;
        indexedPixels = new byte[nPix];
        NeuQuant nq = new NeuQuant(pixels, len, sample);
        colorTab = nq.process();
        for (int i = 0; i < colorTab.length; i += 3) {
            byte temp = colorTab[i];
            colorTab[i] = colorTab[i + 2];
            colorTab[i + 2] = temp;
            usedEntry[i / 3] = false;
        }
        int k = 0;
        for (int i = 0; i < nPix; i++) {
            int index = nq.map(pixels[k++] & 0xff, pixels[k++] & 0xff, pixels[k++] & 0xff);
            usedEntry[index] = true;
            indexedPixels[i] = (byte) index;
        }
        pixels = null;
        colorDepth = 8;
        palSize = 7;
        if (transparent != Color.TRANSPARENT) {
            transIndex = findClosest(transparent);
        }
    }

    protected int findClosest(int c) {
        if (colorTab == null) return -1;
        int r = Color.red(c);
        int g = Color.green(c);
        int b = Color.blue(c);
        int minpos = 0;
        int dmin = 256 * 256 * 256;
        int len = colorTab.length;
        for (int i = 0; i < len;) {
            int dr = r - (colorTab[i++] & 0xff);
            int dg = g - (colorTab[i++] & 0xff);
            int db = b - (colorTab[i] & 0xff);
            int d = dr * dr + dg * dg + db * db;
            int index = i / 3;
            if (usedEntry[index] && (d < dmin)) {
                dmin = d;
                minpos = index;
            }
            i++;
        }
        return minpos;
    }

    protected void getImagePixels() {
        int w = image.getWidth();
        int h = image.getHeight();
        if ((w != width) || (h != height)) {
            Bitmap temp = Bitmap.createScaledBitmap(image, width, height, true);
            image = temp;
        }
        int[] data = new int[width * height];
        image.getPixels(data, 0, width, 0, 0, width, height);
        pixels = new byte[data.length * 3];
        for (int i = 0; i < data.length; i++) {
            int color = data[i];
            int tr = i * 3;
            pixels[tr] = (byte) ((color & 0xFF));
            pixels[tr + 1] = (byte) (((color >> 8) & 0xFF));
            pixels[tr + 2] = (byte) (((color >> 16) & 0xFF));
        }
    }

    protected void writeGraphicCtrlExt() throws IOException {
        out.write(0x21);
        out.write(0xf9);
        out.write(4);
        int transp, disp;
        if (transparent == Color.TRANSPARENT) {
            transp = 0;
            disp = 0;
        } else {
            transp = 1;
            disp = 2;
        }
        if (dispose >= 0) disp = dispose & 7;
        disp <<= 2;
        out.write(0 | disp | 0 | transp);
        writeShort(delay);
        out.write(transIndex);
        out.write(0);
    }

    protected void writeImageDesc() throws IOException {
        out.write(0x2c);
        writeShort(0);
        writeShort(0);
        writeShort(width);
        writeShort(height);
        if (firstFrame) out.write(0);
        else out.write(0x80 | 0 | 0 | 0 | palSize);
    }

    protected void writeLSD() throws IOException {
        writeShort(width);
        writeShort(height);
        out.write((0x80 | 0 | 0 | palSize));
        out.write(0);
        out.write(0);
    }

    protected void writeNetscapeExt() throws IOException {
        out.write(0x21);
        out.write(0xff);
        out.write(11);
        writeString("NETSCAPE2.0");
        out.write(3);
        out.write(1);
        writeShort(repeat);
        out.write(0);
    }

    protected void writePalette() throws IOException {
        out.write(colorTab, 0, colorTab.length);
        int n = (3 * 256) - colorTab.length;
        for (int i = 0; i < n; i++) out.write(0);
    }

    protected void writePixels() throws IOException {
        LZWEncoder encoder = new LZWEncoder(width, height, indexedPixels, colorDepth);
        encoder.encode(out);
    }

    protected void writeShort(int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    protected void writeString(String s) throws IOException {
        for (int i = 0; i < s.length(); i++) out.write((byte) s.charAt(i));
    }
}

class NeuQuant {
    protected static final int netsize = 256;
    protected static final int prime1 = 499;
    protected static final int prime2 = 491;
    protected static final int prime3 = 487;
    protected static final int prime4 = 503;
    protected static final int minpicturebytes = (3 * prime4);
    protected static final int maxnetpos = (netsize - 1);
    protected static final int netbiasshift = 4;
    protected static final int ncycles = 100;
    protected static final int intbiasshift = 16;
    protected static final int intbias = (((int) 1) << intbiasshift);
    protected static final int gammashift = 10;
    protected static final int betashift = 10;
    protected static final int beta = (intbias >> betashift);
    protected static final int betagamma = (intbias << (gammashift - betashift));
    protected static final int radiusbiasshift = 6;
    protected static final int radiusbias = (((int) 1) << radiusbiasshift);
    protected static final int radiusdec = 30;
    protected static final int alphabiasshift = 10;
    protected static final int initalpha = (((int) 1) << alphabiasshift);
    protected int alphadec;
    protected static final int radbiasshift = 8;
    protected static final int radbias = (((int) 1) << radbiasshift);
    protected static final int alpharadbshift = (alphabiasshift + radbiasshift);
    protected static final int alpharadbias = (((int) 1) << alpharNormally I can help with things like this, but I don't seem to have access to that content. You can try again or ask me for something else.
