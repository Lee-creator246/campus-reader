package org.campusreader.reader;

import android.app.Application;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.encryption.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,manifest=Config.NONE)
public class PolicyImportTest {
    private android.content.Context context;
    @Before public void before(){context=RuntimeEnvironment.getApplication();PDFBoxResourceLoader.init(context);}
    @Test public void utf8PreservesExactTextLinesAndRejectsInvalidEncoding()throws Exception{
        String text="第一条：不符合条件者不得报名。\n第二条：另行通知。";
        PolicyImport.Result result=PolicyImport.read(context,new ByteArrayInputStream(("\uFEFF"+text).getBytes(StandardCharsets.UTF_8)),"synthetic.txt","text/plain");assertFalse(result.pdf);assertEquals(text,result.pages.get(0));
        for(byte[] invalid:new byte[][]{{(byte)0xff},{0},"  ".getBytes(StandardCharsets.UTF_8)})try{PolicyImport.text(invalid);fail();}catch(IOException expected){}
    }
    @Test public void limitsFailInsteadOfReturningTruncatedText()throws Exception{
        try{PolicyImport.readBounded(new ByteArrayInputStream(new byte[11]),10);fail();}catch(IOException expected){}
        char[] huge=new char[100001];Arrays.fill(huge,'a');try{PolicyImport.text(new String(huge).getBytes(StandardCharsets.UTF_8));fail();}catch(IOException expected){}
        PolicyImport.LimitedWriter writer=new PolicyImport.LimitedWriter(3,System.nanoTime()+1_000_000_000L);writer.write("abc");try{writer.write("d");fail();}catch(IOException expected){}assertEquals("abc",writer.toString());
        PolicyImport.LimitedWriter expired=new PolicyImport.LimitedWriter(100,System.nanoTime()-1);try{expired.write("a");fail();}catch(IOException expected){}
    }
    @Test public void rejectsUnsupportedAndFakePdfWithoutParsingAsText()throws Exception{
        for(String name:new String[]{"synthetic.docx","fake.pdf"})try{PolicyImport.read(context,new ByteArrayInputStream("text".getBytes(StandardCharsets.UTF_8)),name,"application/octet-stream");fail();}catch(IOException expected){}
    }
    @Test public void actualPdfKeepsPhysicalPageNumbersIncludingBlankPage()throws Exception{
        byte[] bytes=pdf(3,true,false);PolicyImport.Result result=PolicyImport.read(context,new ByteArrayInputStream(bytes),"synthetic.pdf","application/pdf");
        assertTrue(result.pdf);assertEquals(3,result.pages.size());assertTrue(result.pages.get(0).contains("Synthetic policy page 1"));assertEquals("",result.pages.get(1).trim());assertTrue(result.pages.get(2).contains("Synthetic policy page 3"));
    }
    @Test public void scansEncryptionAndPageLimitFailWithoutPartialImport()throws Exception{
        for(byte[] input:new byte[][]{pdf(1,false,false),pdf(1,true,true),pdf(41,true,false)})try{PolicyImport.read(context,new ByteArrayInputStream(input),"synthetic.pdf","application/pdf");fail();}catch(IOException expected){}
    }
    @Test public void cancelledThreadCannotReadOrWriteMoreText()throws Exception{
        Thread.currentThread().interrupt();try{PolicyImport.readBounded(new ByteArrayInputStream(new byte[]{1}),10);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("取消"));}finally{Thread.interrupted();}
    }
    private byte[] pdf(int pages,boolean text,boolean encrypted)throws Exception{
        try(PDDocument document=new PDDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            for(int i=1;i<=pages;i++){PDPage page=new PDPage();document.addPage(page);if(text&&i!=2)try(PDPageContentStream stream=new PDPageContentStream(document,page)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,12);stream.newLineAtOffset(50,700);stream.showText("Synthetic policy page "+i);stream.endText();}}
            if(encrypted)document.protect(new StandardProtectionPolicy("synthetic-owner","synthetic-user",new AccessPermission()));document.save(output);return output.toByteArray();
        }
    }
}
