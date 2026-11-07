package com.gpmanager;

import org.junit.Test;
import static org.junit.Assert.*;

/** Preserve the UTF-16 character gate and the ordered removal of native markup. */
public class ActionLabelSharingContractTest {
    @Test public void everyCodeUnitAndSupplementaryNamesKeepTheirOldEligibility() {
        for(int code=0;code<=Character.MAX_VALUE;code++) {
            String text=String.valueOf((char)code);
            boolean valid=Character.isLetterOrDigit(code)||code==' '||code=='\''||code=='-'||code==',';
            assertEquals("code unit "+code,valid?text:null,ActionLabel.useful(text));
        }
        for(String text:new String[]{"Cast","cASt","ca\u017ft","Autocast","Cancel","", "a".repeat(65),
            "Cast Ice Barrage","\ud801\udc00","a\ud801\udc00b","\ud83d\ude00","\ud800","\udc00"}) {
            boolean valid=!text.isEmpty()&&text.length()<=64&&!text.equalsIgnoreCase("cast")
                &&!text.equalsIgnoreCase("autocast")&&!text.equalsIgnoreCase("cancel")
                &&text.chars().allMatch(c->Character.isLetterOrDigit(c)||c==' '||c=='\''||c=='-'||c==',');
            assertEquals(text,valid?text:null,ActionLabel.useful(text));
        }
    }
    @Test public void markupRemovalStillRunsBeforeEntityAndWhitespaceNormalization() {
        for(String first:new String[]{"","<col=ff0000>","@abc@","<x @abc@>","@ab<x>c@","<x\ny>","<@abc@"})
            for(String second:new String[]{"","&nbsp;","&#160;","@123456@","@1234567@","\n\t Ice ","\u00a0"}) {
                String text=first+second;
                String expected=text.replaceAll("<[^>]*>"," ").replaceAll("@[a-zA-Z0-9]{3,6}@"," ")
                    .replace("&nbsp;"," ").replace("&#160;"," ").replaceAll("\\s+"," ").trim();
                assertEquals(text,expected,ActionLabel.clean(text));
            }
        assertEquals("",ActionLabel.clean(null));
    }
}
