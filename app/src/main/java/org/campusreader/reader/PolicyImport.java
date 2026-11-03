package org.campusreader.reader;

import android.content.Context;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.text.PDFTextStripper;

/** No network, OCR, scripts or embedded attachments. Imports only bounded visible text. */
public final class PolicyImport {
    public static final int MAX_FILE_BYTES=8*1024*1024;
    /** Only these application-owned messages may be displayed; parser/provider errors stay private. */
    public static final class ImportException extends IOException {public ImportException(String message){super(message);}}
    public static final class Result {
        public final List<String> pages;
        public final boolean pdf;
        Result(List<String> pages,boolean pdf){this.pages=Collections.unmodifiableList(new ArrayList<>(pages));this.pdf=pdf;}
    }
    private PolicyImport(){}
    public static Result read(Context context,InputStream input,String filename,String mime)throws IOException{
        byte[] bytes=readBounded(input,MAX_FILE_BYTES);check();
        boolean pdf=bytes.length>=5&&bytes[0]=='%'&&bytes[1]=='P'&&bytes[2]=='D'&&bytes[3]=='F'&&bytes[4]=='-';
        String name=filename==null?"":filename.toLowerCase(Locale.ROOT);
        if(pdf)return pdf(context,bytes);
        if(name.endsWith(".pdf")||"application/pdf".equals(mime))throw new ImportException("不是可识别的 PDF 文件");
        if(!name.endsWith(".txt")&&!"text/plain".equals(mime))throw new ImportException("目前支持 UTF-8 TXT 和文字版 PDF");
        return text(bytes);
    }
    static Result text(byte[] bytes)throws IOException{
        try{
            String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(value.startsWith("\uFEFF"))value=value.substring(1);
            if(value.indexOf('\0')>=0)throw new ImportException("TXT 必须使用 UTF-8 编码");
            if(value.length()>PolicyLibrary.MAX_DOCUMENT_CHARS)throw new ImportException("单份资料最多 100000 字符，没有截取部分内容");
            if(value.trim().isEmpty())throw new ImportException("资料中没有可用文字");
            return new Result(Collections.singletonList(value),false);
        }catch(CharacterCodingException invalid){throw new ImportException("TXT 必须使用 UTF-8 编码，请转换编码后重试");}
    }
    private static Result pdf(Context context,byte[] bytes)throws IOException{
        PDFBoxResourceLoader.init(context.getApplicationContext());long deadline=System.nanoTime()+30_000_000_000L;
        MemoryUsageSetting memory=MemoryUsageSetting.setupMixed(2L*1024*1024,32L*1024*1024);memory.setTempDir(context.getCacheDir());
        try(PDDocument document=PDDocument.load(new ByteArrayInputStream(bytes),memory)){
            check(deadline);if(document.isEncrypted())throw new ImportException("暂不支持加密 PDF，请使用可公开读取的文字版");
            int count=document.getNumberOfPages();if(count<1||count>PolicyLibrary.MAX_PAGES)throw new ImportException("PDF 最多 40 页，没有截取部分内容");
            if(!document.getCurrentAccessPermission().canExtractContent())throw new ImportException("该 PDF 未允许提取文字");
            ArrayList<String> pages=new ArrayList<>();int total=0;boolean hasText=false;
            PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);
            for(int page=1;page<=count;page++){
                check(deadline);stripper.setStartPage(page);stripper.setEndPage(page);
                LimitedWriter writer=new LimitedWriter(PolicyLibrary.MAX_DOCUMENT_CHARS-total,deadline);stripper.writeText(document,writer);
                String text=writer.toString();total+=text.length();hasText|=!text.trim().isEmpty();pages.add(text);
            }
            if(!hasText)throw new ImportException("PDF 没有可提取文字；扫描件需先转换为文字版，本版不进行 OCR");
            return new Result(pages,true);
        }catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException encrypted){throw new ImportException("暂不支持加密 PDF，请使用无需密码的文字版");}
    }
    static byte[] readBounded(InputStream input,int limit)throws IOException{
        if(input==null)throw new ImportException("无法打开所选文件");ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;long deadline=System.nanoTime()+30_000_000_000L;
        while((count=input.read(buffer))!=-1){check(deadline);if(output.size()+count>limit)throw new ImportException("文件超过本机导入容量上限");output.write(buffer,0,count);}return output.toByteArray();
    }
    private static void check()throws IOException{if(Thread.currentThread().isInterrupted())throw new ImportException("导入已取消");}
    private static void check(long deadline)throws IOException{check();if(System.nanoTime()>deadline)throw new ImportException("资料解析耗时过长，请使用较小的文字文件");}
    static final class LimitedWriter extends Writer {
        final StringBuilder value=new StringBuilder();final int limit;final long deadline;
        LimitedWriter(int limit,long deadline){this.limit=limit;this.deadline=deadline;}
        @Override public void write(char[] chars,int offset,int count)throws IOException{check(deadline);if(value.length()+count>limit)throw new ImportException("单份资料最多 100000 字符，没有截取部分内容");value.append(chars,offset,count);}
        @Override public void flush(){}@Override public void close(){}@Override public String toString(){return value.toString();}
    }
}
