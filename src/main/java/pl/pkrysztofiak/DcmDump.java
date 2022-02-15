package pl.pkrysztofiak;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.ParseException;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.ElementDictionary;
import org.dcm4che3.data.Fragments;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputHandler;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.tool.common.CLIUtils;
import org.dcm4che3.util.TagUtils;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 */
public class DcmDump implements DicomInputHandler {

    private static ResourceBundle rb =
            ResourceBundle.getBundle("org.dcm4che3.tool.dcmdump.messages");

    /** default number of characters per line */
    private static final int DEFAULT_WIDTH = 78;

    private int width = DEFAULT_WIDTH;

    public final int getWidth() {
        return width;
    }

    public final void setWidth(int width) {
        if (width < 40)
            throw new IllegalArgumentException();
        this.width = width;
    }

    public void parse(DicomInputStream dis) throws IOException {
        dis.setDicomInputHandler(this);
        dis.readDataset();
    }

    @Override
    public void startDataset(DicomInputStream dis) throws IOException {
        promptPreamble(dis.getPreamble());
    }

    @Override
    public void endDataset(DicomInputStream dis) throws IOException {
    }

    @Override
    public void readValue(DicomInputStream dis, Attributes attrs)
            throws IOException {
        StringBuilder line = new StringBuilder(width + 30);
        appendPrefix(dis, line);
        appendHeader(dis, line);
        VR vr = dis.vr();
        int vallen = dis.length();
        boolean undeflen = vallen == -1;
        int tag = dis.tag();
        String privateCreator = attrs.getPrivateCreator(tag);
        if (vr == VR.SQ || undeflen) {
            appendKeyword(dis, privateCreator, line);
            System.out.println(line);
            dis.readValue(dis, attrs);
            if (undeflen) {
                line.setLength(0);
                appendPrefix(dis, line);
                appendHeader(dis, line);
                appendKeyword(dis, privateCreator, line);
                System.out.println(line);
            }
            return;
        }
        byte[] b = dis.readValue();
        line.append(" [");
        if (vr.prompt(b, dis.bigEndian(),
                attrs.getSpecificCharacterSet(),
                width - line.length() - 1, line)) {
            line.append(']');
            appendKeyword(dis, privateCreator, line);
        }
        System.out.println(line);
        if (tag == Tag.FileMetaInformationGroupLength)
            dis.setFileMetaInformationGroupLength(b);
        else if (tag == Tag.TransferSyntaxUID
                || tag == Tag.SpecificCharacterSet
                || TagUtils.isPrivateCreator(tag))
            attrs.setBytes(tag, vr, b);
    }

    @Override
    public void readValue(DicomInputStream dis, Sequence seq)
            throws IOException {
        String privateCreator = seq.getParent().getPrivateCreator(dis.tag());
        StringBuilder line = new StringBuilder(width);
        appendPrefix(dis, line);
        appendHeader(dis, line);
        appendKeyword(dis, privateCreator, line);
        appendNumber(seq.size() + 1, line);
        System.out.println(line);
        boolean undeflen = dis.length() == -1;
        dis.readValue(dis, seq);
        if (undeflen) {
            line.setLength(0);
            appendPrefix(dis, line);
            appendHeader(dis, line);
            appendKeyword(dis, privateCreator, line);
            System.out.println(line);
        }
    }

    @Override
    public void readValue(DicomInputStream dis, Fragments frags)
            throws IOException {
        StringBuilder line = new StringBuilder(width + 20);
        appendPrefix(dis, line);
        appendHeader(dis, line);
        appendFragment(line, dis, frags.vr());
        System.out.println(line);
    }

    private void appendPrefix(DicomInputStream dis, StringBuilder line) {
        line.append(dis.getTagPosition()).append(": ");
        int level = dis.level();
        while (level-- > 0)
            line.append('>');
    }

    private void appendHeader(DicomInputStream dis, StringBuilder line) {
        line.append(TagUtils.toString(dis.tag())).append(' ');
        VR vr = dis.vr();
        if (vr != null)
            line.append(vr).append(' ');
        line.append('#').append(dis.length());
    }

    private void appendKeyword(DicomInputStream dis, String privateCreator, StringBuilder line) {
        if (line.length() < width) {
            line.append(" ");
            line.append(ElementDictionary.keywordOf(dis.tag(), privateCreator));
            if (line.length() > width)
                line.setLength(width);
        }
    }

    private void appendNumber(int number, StringBuilder line) {
        if (line.length() < width) {
            line.append(" #");
            line.append(number);
            if (line.length() > width)
                line.setLength(width);
        }
    }

    private void appendFragment(StringBuilder line, DicomInputStream dis,
                                VR vr) throws IOException {
        byte[] b = dis.readValue();
        line.append(" [");
        if (vr.prompt(b, dis.bigEndian(), null,
                width - line.length() - 1, line)) {
            line.append(']');
            appendKeyword(dis, null, line);
        }
    }

    private void promptPreamble(byte[] preamble) {
        if (preamble == null)
            return;

        StringBuilder line = new StringBuilder(width);
        line.append("0: [");
        if (VR.OB.prompt(preamble, false, null, width - 5, line))
            line.append(']');
        System.out.println(line);
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        try {
            CommandLine cl = parseComandLine(args);
            DcmDump main = new DcmDump();
            if (cl.hasOption("w")) {
                String s = cl.getOptionValue("w");
                try {
                    main.setWidth(Integer.parseInt(s));
                } catch (IllegalArgumentException e) {
                    throw new ParseException(MessageFormat.format(
                            rb.getString("illegal-width"), s));
                }
            }
            String fname = fname(cl.getArgList());
            if (fname.equals("-")) {
                main.parse(new DicomInputStream(System.in));
            } else {
                DicomInputStream dis =
                        new DicomInputStream(new File(fname));
                try {
                    main.parse(dis);
                } finally {
                    dis.close();
                }
            }
        } catch (ParseException e) {
            System.err.println("dcmdump: " + e.getMessage());
            System.err.println(rb.getString("try"));
            System.exit(2);
        } catch (IOException e) {
            System.err.println("dcmdump: " + e.getMessage());
            System.exit(2);
        }
    }

    private static String fname(List<String> argList) throws ParseException {
        int numArgs = argList.size();
        if (numArgs == 0)
            throw new ParseException(rb.getString("missing"));
        if (numArgs > 1)
            throw new ParseException(rb.getString("too-many"));
        return argList.get(0);
    }

    @SuppressWarnings("static-access")
    private static CommandLine parseComandLine(String[] args)
            throws ParseException{
        Options opts = new Options();
        CLIUtils.addCommonOptions(opts);
        opts.addOption(Option.builder("w")
                .longOpt("width")
                .hasArg()
                .argName("col")
                .desc(rb.getString("width"))
                .build());
        return CLIUtils.parseComandLine(args, opts, rb, DcmDump.class);
    }

}
