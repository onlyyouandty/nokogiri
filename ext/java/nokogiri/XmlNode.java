package nokogiri;

import static java.lang.Math.max;
import static nokogiri.internals.NokogiriHelpers.getCachedNodeOrCreate;
import static nokogiri.internals.NokogiriHelpers.nodeArrayToRubyArray;
import static nokogiri.internals.NokogiriHelpers.nonEmptyStringOrNil;
import static nokogiri.internals.NokogiriHelpers.rubyStringToString;
import static nokogiri.internals.NokogiriHelpers.stringOrNil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import nokogiri.internals.HtmlDomParserContext;
import nokogiri.internals.NokogiriHelpers;
import nokogiri.internals.NokogiriNamespaceCache;
import nokogiri.internals.NokogiriNamespaceContext;
import nokogiri.internals.NokogiriUserDataHandler;
import nokogiri.internals.SaveContext;
import nokogiri.internals.XmlDomParserContext;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyFixnum;
import org.jruby.RubyModule;
import org.jruby.RubyObject;
import org.jruby.RubyString;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.RaiseException;
import org.jruby.javasupport.util.RuntimeHelpers;
import org.jruby.runtime.Block;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

@JRubyClass(name="Nokogiri::XML::Node")
public class XmlNode extends RubyObject {

    /** The underlying Node object. */
    protected Node node;

    /* Cached objects */
    protected IRubyObject content = null;
    protected IRubyObject doc = null;
    protected IRubyObject name = null;
    protected IRubyObject namespace = null;
    protected IRubyObject namespace_definitions = null;

    /*
     * Taken from http://ejohn.org/blog/comparing-document-position/
     * Used for compareDocumentPosition.
     * <ironic>Thanks to both java api and w3 doc for its helpful documentation</ironic>
     */

    protected static final int IDENTICAL_ELEMENTS = 0;
    protected static final int IN_DIFFERENT_DOCUMENTS = 1;
    protected static final int SECOND_PRECEDES_FIRST = 2;
    protected static final int FIRST_PRECEDES_SECOND = 4;
    protected static final int SECOND_CONTAINS_FIRST = 8;
    protected static final int FIRST_CONTAINS_SECOND = 16;

    /**
     * Cast <code>node</code> to an XmlNode or raise a type error
     * in <code>context</code>.
     */
    protected static XmlNode asXmlNode(ThreadContext context, IRubyObject node) {
        return _asXmlNode(context, node, false);
    }

    /**
     * Cast <code>node</code> to an XmlNode, or null if RubyNil, or
     * raise a type error in <code>context</code>.
     */
    protected static XmlNode asXmlNodeOrNull(ThreadContext context, IRubyObject node) {
        return _asXmlNode(context, node, true);
    }

    /**
     * Get the XmlNode associated with the underlying
     * <code>node</code>. Throws an exception if there is no XmlNode.
     */
    public static XmlNode fromNode(ThreadContext context, Node node) {
        if (node == null)
            throw context.getRuntime().newRuntimeError("node is null");

        XmlNode xnode = (XmlNode) node.getUserData(NokogiriUserDataHandler.CACHED_NODE);
        if (xnode == null)
            throw context.getRuntime().newRuntimeError("no cached XmlNode");

        return xnode;
    }

    /**
     * Get the XmlNode associated with the underlying
     * <code>node</code>. Creates a new XmlNode (or appropriate
     * subclass) wrapping <code>node</code> if there is no cached
     * value.
     */
    public static IRubyObject fromNodeOrCreate(ThreadContext context,
                                               Node node) {
        Ruby ruby = context.getRuntime();
        if (node == null) return ruby.getNil();
        XmlNode xmlNode =
            (XmlNode) node.getUserData(NokogiriUserDataHandler.CACHED_NODE);
        if (xmlNode == null) {
            xmlNode = (XmlNode) XmlNode.constructNode(ruby, node);
            node.setUserData(NokogiriUserDataHandler.CACHED_NODE, xmlNode,
                    new NokogiriUserDataHandler(ruby));
        }
        return xmlNode;
    }

    /**
     * Cast <code>node</code> to an XmlNode if possible.  If
     * <code>allowNil</code> is true and node is Ruby nil, returns
     * null.  Otherwise, raise a type error in <code>context</code>.
     */
    private static XmlNode _asXmlNode(ThreadContext context,
                                      IRubyObject node,
                                      boolean allowNil) {
        if (allowNil && (node == null || node.isNil())) {
            return null;
        } else if (!(node instanceof XmlNode)) {
            Ruby ruby = context.getRuntime();
            throw ruby.newTypeError(node,(RubyClass) ruby.getClassFromPath("Nokogiri::XML::Node"));
        } else {
            return (XmlNode) node;
        }
    }

    /**
     * Coalesce to adjacent TextNodes.
     * @param context
     * @param prev Previous node to cur.
     * @param cur Next node to prev.
     */
    public static void coalesceTextNodes(ThreadContext context, IRubyObject prev, IRubyObject cur) {
        XmlNode p = asXmlNode(context, prev);
        XmlNode c = asXmlNode(context, cur);

        Node pNode = p.node;
        Node cNode = c.node;

        pNode.setNodeValue(pNode.getNodeValue()+cNode.getNodeValue());
        p.content = null;       // clear cached content

        c.assimilateXmlNode(context, p);
    }

    /**
     * Coalesce text nodes around <code>anchorNode</code>.  If
     * <code>anchorNode</code> has siblings (previous or next) that
     * are text nodes, the content will be merged into
     * <code>anchorNode</code> and the redundant nodes will be removed
     * from the DOM.
     *
     * To match libxml behavior (?) the final content of
     * <code>anchorNode</code> and any removed nodes will be
     * identical.
     *
     * @param context
     * @param anchorNode
     */
    protected static void coalesceTextNodes(ThreadContext context,
                                            IRubyObject anchorNode) {
        XmlNode xa = asXmlNode(context, anchorNode);

        XmlNode xp = asXmlNodeOrNull(context, xa.previous_sibling(context));
        XmlNode xn = asXmlNodeOrNull(context, xa.next_sibling(context));

        Node p = xp == null ? null : xp.node;
        Node a = xa.node;
        Node n = xn == null ? null : xn.node;

        Node parent = a.getParentNode();

        if(a.getNodeType() == Node.TEXT_NODE) {
            if(p != null && p.getNodeType() == Node.TEXT_NODE) {
                xa.setContent(p.getNodeValue() + a.getNodeValue());
                parent.removeChild(p);
                xp.assimilateXmlNode(context, xa);
            } else if(n != null && n.getNodeType() == Node.TEXT_NODE) {
                xa.setContent(a.getNodeValue() + n.getNodeValue());
                parent.removeChild(n);
                xn.assimilateXmlNode(context, xa);
            }
        }
    }

    /**
     * Construct a new XmlNode wrapping <code>node</code>.  The proper
     * subclass of XmlNode is chosen based on the type of
     * <code>node</code>.
     */
    public static IRubyObject constructNode(Ruby ruby, Node node) {
        if (node == null) return ruby.getNil();
        // this is slow; need a way to cache nokogiri classes/modules somewhere
        switch (node.getNodeType()) {
            case Node.ATTRIBUTE_NODE:
                return new XmlAttr(ruby, node);
            case Node.TEXT_NODE:
                return new XmlText(ruby, (RubyClass)ruby.getClassFromPath("Nokogiri::XML::Text"), node);
            case Node.COMMENT_NODE:
                return new XmlComment(ruby, (RubyClass)ruby.getClassFromPath("Nokogiri::XML::Comment"), node);
            case Node.ELEMENT_NODE:
                return new XmlElement(ruby, (RubyClass)ruby.getClassFromPath("Nokogiri::XML::Element"), node);
            case Node.ENTITY_NODE:
                return new XmlNode(ruby, (RubyClass)ruby.getClassFromPath("Nokogiri::XML::EntityDeclaration"), node);
            case Node.CDATA_SECTION_NODE:
                return new XmlCdata(ruby, (RubyClass)ruby.getClassFromPath("Nokogiri::XML::CDATA"), node);
            case Node.DOCUMENT_NODE:
                return new XmlDocument(ruby, (Document) node);
            default:
                return new XmlNode(ruby, (RubyClass)ruby.getClassFromPath("Nokogiri::XML::Node"), node);
        }
    }

    /**
     * This is the allocator for XmlNode class.  It should only be
     * called from Ruby code.
     */
    public XmlNode(Ruby ruby, RubyClass cls) {
        this(ruby, cls, null);
    }

    /**
     * This is a constructor to create an XmlNode from an already
     * existing node.  It may be called by Java code.
     */
    public XmlNode(Ruby ruby, RubyClass cls, Node node) {
        super(ruby, cls);
        this.node = node;

        if (node != null) {
            resetCache();

            if (node.getNodeType() != Node.DOCUMENT_NODE) {
                XmlNode owner = (XmlNode) this.document(ruby.getCurrentContext());

                if (owner != null && owner instanceof XmlDocument) {
                    RuntimeHelpers.invoke(ruby.getCurrentContext(),
                                          owner, "decorate", this);
                }
            }
        }
    }

    /**
     * Allocate a new object, perform initialization, call that
     * object's initialize method, and call any block passing the
     * object as the only argument.  If <code>cls</code> is
     * Nokogiri::XML::Node, creates a new Nokogiri::XML::Element
     * instead.
     *
     * This static method seems to be inherited, strangely enough.
     * E.g. creating a new XmlAttr from Ruby code calls this method if
     * XmlAttr does not define its own 'new' method.
     *
     * Since there is some Java bookkeeping that always needs to
     * happen, we don't define the 'initialize' method in Java because
     * we'd have to count on subclasses calling 'super'.
     *
     * The main consequence of this is that every subclass needs to
     * define its own 'new' method.
     *
     * As a convenience, this method does the following:
     *
     * <ul>
     *
     * <li>allocates a new object using the allocator assigned to
     * <code>cls</code></li>
     *
     * <li>calls the Java method init(); subclasses can override this,
     * otherwise they should implement a specific 'new' method</li>
     *
     * <li>invokes the Ruby initializer</li>
     *
     * <li>if a block is given, calls the block with the new node as
     * the argument</li>
     *
     * </ul>
     *
     * -pmahoney
     */
    @JRubyMethod(name = "new", meta = true, rest = true)
    public static IRubyObject rbNew(ThreadContext context, IRubyObject cls,
                                    IRubyObject[] args, Block block) {
        Ruby ruby = context.getRuntime();
        RubyClass klazz = (RubyClass) cls;

        if (cls.equals(ruby.getClassFromPath("Nokogiri::XML::Node"))) {
            klazz = (RubyClass) ruby.getClassFromPath("Nokogiri::XML::Element");
        }

        XmlNode node = (XmlNode) klazz.allocate();
        node.init(context, args);
        node.callInit(args, block);
        if (node.node == null) System.out.println("NODE IS NULL");
        if (block.isGiven()) block.call(context, node);
        return node;
    }

    /**
     * Initialize the object from Ruby arguments.  Should be
     * overridden by subclasses.  Should check for a minimum number of
     * args but not for an exact number.  Any extra args will then be
     * passed to 'initialize'.  The way 'new' and this 'init' function
     * interact means that subclasses cannot arbitrarily change the
     * require aruments by defining an 'initialize' method.  This is
     * how the C libxml wrapper works also.
     *
     * As written it performs initialization for a new Element with
     * the given <code>name</code> within the document
     * <code>doc</code>.  So XmlElement need not override this.  This
     * implementation cannot be moved to XmlElement however, because
     * subclassing XmlNode must result in something that behaves much
     * like XmlElement.
     */
    protected void init(ThreadContext context, IRubyObject[] args) {
        if (args.length < 2)
            throw context.getRuntime().newArgumentError(args.length, 2);

        IRubyObject name = args[0];
        IRubyObject doc = args[1];

        Document document = asXmlNode(context, doc).getOwnerDocument();
        if (document == null) {
            throw getRuntime().newArgumentError("node must have owner document");
        }
        XmlDocument xmlDoc =
            (XmlDocument) getCachedNodeOrCreate(getRuntime(), document);

        Element element =
            document.createElementNS(null, rubyStringToString(name));
        setNode(element);
        setDocument(xmlDoc);
        RuntimeHelpers.invoke(context, xmlDoc, "decorate", this);
    }

    public void resetCache() {
        node.setUserData(NokogiriUserDataHandler.CACHED_NODE, this,
                         new NokogiriUserDataHandler(getRuntime()));
    }


    /**
     * Set the underlying node of this node to the underlying node of
     * <code>otherNode</code>.
     *
     * FIXME: also update the cached node?
     */
    protected void assimilateXmlNode(ThreadContext context, IRubyObject otherNode) {
        XmlNode toAssimilate = asXmlNode(context, otherNode);

        this.node = toAssimilate.node;
        content = null;         // clear cache
    }

    /**
     * See org.w3.dom.Node#normalize.
     */
    public void normalize() {
        node.normalize();
    }

    public Node getNode() {
        return node;
    }

    public static Node getNodeFromXmlNode(ThreadContext context, IRubyObject xmlNode) {
        return asXmlNode(context, xmlNode).node;
    }

    protected String indentString(IRubyObject indentStringObject, String xml) {
        String[] lines = xml.split("\n");

        if(lines.length <= 1) return xml;

        String[] resultLines  = new String[lines.length];

        String curLine;
        boolean closingTag = false;
        String indentString = indentStringObject.convertToString().asJavaString();
        int lengthInd = indentString.length();
        StringBuffer curInd = new StringBuffer();

        resultLines[0] = lines[0];

        for(int i = 1; i < lines.length; i++) {

            curLine = lines[i].trim();

            if(curLine.length() == 0) continue;

            if(curLine.startsWith("</")) {
                closingTag = true;
                curInd.setLength(max(0,curInd.length() - lengthInd));
            }

            resultLines[i] = curInd.toString() + curLine;
            
            if(!curLine.endsWith("/>") && !closingTag) {
                curInd.append(indentString);
            }

            closingTag = false;
        }

        StringBuffer result = new StringBuffer();
        for(int i = 0; i < resultLines.length; i++) {
            result.append(resultLines[i]);
            result.append("\n");
        }

        return result.toString();
    }

    public boolean isComment() { return false; }

    public boolean isElement() { return false; }

    public boolean isProcessingInstruction() { return false; }

    protected IRubyObject parseRubyString(Ruby ruby, RubyString content) {
        try {
            Document document;
            ByteList byteList = content.getByteList();
            ByteArrayInputStream bais = new ByteArrayInputStream(byteList.unsafeBytes(), byteList.begin(), byteList.length());
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setIgnoringElementContentWhitespace(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            db.setEntityResolver(new EntityResolver() {
                public InputSource resolveEntity(String arg0, String arg1) throws SAXException, IOException {
                    return new InputSource(new ByteArrayInputStream(new byte[0]));
                }
            });
            document = db.parse(bais);
            return constructNode(ruby, document.getFirstChild());
        } catch (ParserConfigurationException pce) {
            throw RaiseException.createNativeRaiseException(ruby, pce);
        } catch (SAXException saxe) {
            throw RaiseException.createNativeRaiseException(ruby, saxe);
        } catch (IOException ioe) {
            throw RaiseException.createNativeRaiseException(ruby, ioe);
        }
    }

    /**
     * Return the string value of the attribute <code>key</code> or
     * nil.
     *
     * Only applies where the underlying Node is an Element node, but
     * implemented here in XmlNode because not all nodes with
     * underlying Element nodes subclass XmlElement, such as the DTD
     * declarations like XmlElementDecl.
     */
    protected IRubyObject getAttribute(ThreadContext context, String key) {
        return getAttribute(context.getRuntime(), key);
    }

    protected IRubyObject getAttribute(Ruby runtime, String key) {
        String value = getAttribute(key);
        return nonEmptyStringOrNil(runtime, value);
    }

    protected String getAttribute(String key) {
        if (!(node instanceof Element)) return null;

        String value = ((Element)node).getAttribute(key);
        return value.length() == 0 ? null : value;
    }


    public void post_add_child(ThreadContext context, XmlNode current, XmlNode child) {
    }

    public void setNamespaceDefinitions(IRubyObject namespace_definitions) {
        this.namespace_definitions = namespace_definitions;
    }

    public void relink_namespace(ThreadContext context) {
        //this should delegate to subclasses' implementation
    }

    public void saveContent(ThreadContext context, SaveContext ctx) {
    }

    public void setName(IRubyObject name) {
        this.name = name;
    }

    public void setDocument(IRubyObject doc) {
        this.doc = doc;
    }

    protected void setNode(Node node) {
        this.node = node;
        resetCache();
    }

    public void updateNodeNamespaceIfNecessary(ThreadContext context, XmlNamespace ns) {
        String oldPrefix = this.node.getPrefix();
        String uri = ns.href(context).convertToString().asJavaString();

        /*
         * Update if both prefixes are null or equal
         */
        boolean update = (oldPrefix == null && ns.prefix(context).isNil()) ||
                            (oldPrefix != null && !ns.prefix(context).isNil()
                && oldPrefix.equals(ns.prefix(context).convertToString().asJavaString()));

        if(update) {
            this.node.getOwnerDocument().renameNode(this.node, uri, this.node.getNodeName());
            this.namespace = ns;
        }
    }
    
    public void removeNamespace(ThreadContext context) {
        this.namespace = context.getRuntime().getNil();
    }

    public RubyString getNodeName(ThreadContext context) {
        String str = null;

        if (this.name == null && node != null) {
            str = node.getNodeName();
            if (str == null) { str = ""; }

            if(str.equals("#document")) {
                str = "document";
            } else if(str.equals("#text")) {
                str = "text";
            } else {
                str = NokogiriHelpers.getLocalPart(str);
            }

            if (str == null) str = "";
            this.name = context.getRuntime().newString(str);
        }

        return (RubyString) this.name;
    }

    protected void saveNodeListContent(ThreadContext context, XmlNodeSet list, SaveContext ctx) {
        saveNodeListContent(context, (RubyArray) list.to_a(context), ctx);
    }

    protected void saveNodeListContent(ThreadContext context, RubyArray array, SaveContext ctx) {
        int length = array.getLength();

        boolean formatIndentation = ctx.format() && ctx.indentString()!=null;

        for(int i = 0; i < length; i++) {
            Object item = array.get(i);
            if (item instanceof XmlNode) {
              XmlNode cur = (XmlNode) item;

            // if(formatIndentation &&
            //         (cur.isElement() || cur.isComment() || cur.isProcessingInstruction())) {
            //     ctx.append(ctx.getCurrentIndentString());
            // }

              cur.saveContent(context, ctx);
            } else if (item instanceof XmlNamespace) {
                XmlNamespace cur = (XmlNamespace)item;
                cur.saveContent(context, ctx);
            }

            // if(ctx.format()) ctx.append("\n");
        }
    }

    @JRubyMethod
    public IRubyObject add_child_node(ThreadContext context, IRubyObject child) {
        adoptAs(context, AdoptScheme.CHILD, child);
        return child;
    }

    /**
     * Add a namespace definition to this node.  To the underlying
     * node, add an attribute of the form
     * <code>xmlns:prefix="uri"</code>.
     */
    @JRubyMethod
    public IRubyObject add_namespace_definition(ThreadContext context,
                                                IRubyObject prefix,
                                                IRubyObject href) {
        String prefixString = prefix.isNil() ? "" : rubyStringToString(prefix);
        String hrefString = rubyStringToString(href);
        XmlDocument xmlDocument = (XmlDocument) doc;
        XmlNamespace ns = xmlDocument.getNamespaceCache().put(context.getRuntime(), prefixString, hrefString, node, xmlDocument);

        namespace_definitions = null; // clear cache
        return ns;
    }

    @JRubyMethod
    public IRubyObject attribute(ThreadContext context, IRubyObject name){
        NamedNodeMap attrs = this.node.getAttributes();
        Node attr = attrs.getNamedItem(name.convertToString().asJavaString());
        if(attr == null) {
            return  context.getRuntime().newString(ERR_INSECURE_SET_INST_VAR);
        }
        return constructNode(context.getRuntime(), attr);
    }

    @JRubyMethod()
    public IRubyObject attribute_nodes(ThreadContext context) {
        NamedNodeMap nodeMap = this.node.getAttributes();

        Ruby ruby = context.getRuntime();
        if(nodeMap == null){
            return ruby.newEmptyArray();
        }

        RubyArray attr = ruby.newArray();

        for(int i = 0; i < nodeMap.getLength(); i++) {
            if (!NokogiriHelpers.isNamespace(nodeMap.item(i))) {
                attr.append(fromNodeOrCreate(context, nodeMap.item(i)));
            }
        }

        return attr;
    }

    @JRubyMethod
    public IRubyObject attribute_with_ns(ThreadContext context, IRubyObject name, IRubyObject namespace) {
        String namej = name.convertToString().asJavaString();
        String nsj = (namespace.isNil()) ? null : namespace.convertToString().asJavaString();

        Node el = this.node.getAttributes().getNamedItemNS(nsj, namej);

        if(el == null) {
            return context.getRuntime().getNil();
        }
        return NokogiriHelpers.getCachedNodeOrCreate(context.getRuntime(), el);
    }

    @JRubyMethod(name = "blank?")
    public IRubyObject blank_p(ThreadContext context) {
        String data = node.getTextContent();
        if ("".equals(data.trim())) return context.getRuntime().getTrue();
        return context.getRuntime().getFalse();
    }

    @JRubyMethod
    public IRubyObject child(ThreadContext context) {
        return fromNodeOrCreate(context, node.getFirstChild());
    }

    @JRubyMethod
    public IRubyObject children(ThreadContext context) {
        XmlNodeSet result = new XmlNodeSet(context.getRuntime(),
                                           node.getChildNodes());
        XmlDocument xmlDocument = (XmlDocument) fromNode(context, this.getOwnerDocument());
        result.setDocument(xmlDocument);
        return result;
    }
    
    @JRubyMethod
    public IRubyObject first_element_child(ThreadContext context) {
        List<Node> elementNodes = new ArrayList<Node>();
        addElements(this.getNode(), elementNodes, true);
        if (elementNodes.size() == 0) return context.getRuntime().getNil();
        return fromNodeOrCreate(context, elementNodes.get(0));
    }

    @JRubyMethod
    public IRubyObject last_element_child(ThreadContext context) {
        List<Node> elementNodes = new ArrayList<Node>();
        addElements(this.getNode(), elementNodes, false);
        if (elementNodes.size() == 0) return context.getRuntime().getNil();
        return fromNodeOrCreate(context, elementNodes.get(elementNodes.size()-1));
    }
    
    @JRubyMethod
    public IRubyObject element_children(ThreadContext context) {
        List<Node> elementNodes = new ArrayList<Node>();
        addElements(this.getNode(), elementNodes, false);
        if (elementNodes.size() == 0) return XmlNodeSet.newEmptyNodeSet(context);
        RubyArray array = NokogiriHelpers.nodeArrayToRubyArray(context.getRuntime(), elementNodes.toArray(new Node[0]));
        XmlNodeSet result = new XmlNodeSet(context.getRuntime(), array);
        result.setDocument((XmlDocument) fromNode(context, this.getOwnerDocument()));
        return result;
    }
    
    private void addElements(Node n, List<Node> nodes, boolean isFirstOnly) {
        NodeList children = n.getChildNodes();
        if (children.getLength() == 0) return;
        for (int i=0; i< children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                nodes.add(child);
                if (isFirstOnly) return;
            }
            addElements(child, nodes, isFirstOnly);
        }
    }

    @JRubyMethod
    public IRubyObject compare(ThreadContext context, IRubyObject other) {
        if(!(other instanceof XmlNode)) {
            return context.getRuntime().newFixnum(-2);
        }

        Node otherNode = asXmlNode(context, other).node;

        // Do not touch this if, if it's not for a good reason.
        if(node.getNodeType() == Node.DOCUMENT_NODE ||
           otherNode.getNodeType() == Node.DOCUMENT_NODE) {
            return context.getRuntime().newFixnum(-1);
        }

        try{
            int res = node.compareDocumentPosition(otherNode);
            if( (res & FIRST_PRECEDES_SECOND) == FIRST_PRECEDES_SECOND) {
                return context.getRuntime().newFixnum(-1);
            } else if ( (res & SECOND_PRECEDES_FIRST) == SECOND_PRECEDES_FIRST) {
                return context.getRuntime().newFixnum(1);
            } else if ( res == IDENTICAL_ELEMENTS) {
                return context.getRuntime().newFixnum(0);
            }

            return context.getRuntime().newFixnum(-2);
        } catch (Exception ex) {
            return context.getRuntime().newFixnum(-2);
        }
    }

    /**
     * TODO: this is a stub implementation.  It's not clear what
     * 'in_context' is supposed to do.  Also should take
     * <code>options</code> into account.
     */
    @JRubyMethod(visibility=Visibility.PRIVATE)
    public IRubyObject in_context(ThreadContext context,
                                  IRubyObject str,
                                  IRubyObject options) {
        RubyModule klass;
        XmlDomParserContext ctx;
        InputStream istream;
        XmlDocument document;
        
        IRubyObject d = document(context);
        if (d != null && d instanceof XmlDocument) {
            document = (XmlDocument)d;
        } else {
            return context.getRuntime().getNil();
        }
        
        if (document instanceof HtmlDocument) {
            klass = getRuntime().getClassFromPath("Nokogiri::HTML::Document");
            ctx = new HtmlDomParserContext(getRuntime(), options);
            ((HtmlDomParserContext)ctx).enableDocumentFragment();
            istream = new ByteArrayInputStream(((String)str.toJava(String.class)).getBytes());
        } else {
            klass = getRuntime().getClassFromPath("Nokogiri::XML::Document");
            ctx = new XmlDomParserContext(getRuntime(), options);
            String input = addTemporaryRootTagIfNecessary(context, (String)str.toJava(String.class));
            istream = new ByteArrayInputStream(input.getBytes());
        }

        ctx.setInputSource(istream);
        XmlDocument doc = ctx.parse(context, klass, getRuntime().getNil());
        
        if (isErrorIncreated(document, doc)) {
            saveErrorsOfCreatedDocument(document, doc);
            return new XmlNodeSet(getRuntime(), RubyArray.newArray(context.getRuntime()));
        }
        NodeList childNodes = removeTemporaryRootIfNecessary(doc.getNode().getChildNodes());
        XmlNodeSet nodes = new XmlNodeSet(getRuntime(), childNodes);
        nodes.setInstanceVariable("@document", doc);
        return nodes;
    }
    
    private int getErrorNumbers(XmlDocument document) {
        IRubyObject obj = document.getInstanceVariable("@errors");
        if (obj != null && obj instanceof RubyArray) {
            return ((RubyArray)obj).getLength();
        }
        return 0;
    }
    
    private boolean isErrorIncreated(XmlDocument base, XmlDocument created) {
        int baseDocumentErrors = getErrorNumbers(base);
        int createdDocumentErrors = getErrorNumbers(created);
        return createdDocumentErrors > baseDocumentErrors;
    }
    
    private void saveErrorsOfCreatedDocument(XmlDocument base, XmlDocument created) {
        RubyArray newErrors = (RubyArray)created.getInstanceVariable("@errors");
        RubyArray existingErrors = null;
        IRubyObject obj = base.getInstanceVariable("@errors");
        if (obj != null && obj instanceof RubyArray) {
            existingErrors = (RubyArray)obj;
        } else {
            existingErrors = RubyArray.newArray(base.getRuntime());
        }
        for (int i=0; i<newErrors.getLength(); i++) {
            existingErrors.add(newErrors.get(i));
        }
        base.setInstanceVariable("@errors", existingErrors);
    }
    
    private String addTemporaryRootTagIfNecessary(ThreadContext context, String tags) {
        Matcher matcher = XmlDocumentFragment.wellformed_pattern.matcher(tags);
        while(matcher.find()) {
            if (matcher.start() == 0 && matcher.end() == tags.length()) return tags;
            break;
        }
        tags = "<"+ NokogiriNamespaceContext.NOKOGIRI_TEMPORARY_ROOT_TAG + ">" + tags + "</" + NokogiriNamespaceContext.NOKOGIRI_TEMPORARY_ROOT_TAG + ">";
        return tags;
    }
    
    private NodeList removeTemporaryRootIfNecessary(NodeList nodes) {
        if (nodes != null && nodes.getLength() > 0) {
            Node n = nodes.item(0);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(NokogiriNamespaceContext.NOKOGIRI_TEMPORARY_ROOT_TAG)) {
                return n.getChildNodes();
            }
        }
        return nodes;
    }

    @JRubyMethod
    public IRubyObject content(ThreadContext context) {
        if(this.content == null) {
            String textContent;
            if (this instanceof XmlDocument) {
                textContent = this.node.getChildNodes().item(0).getTextContent();
            } else {
                textContent = this.node.getTextContent();
            }
            content = stringOrNil(context.getRuntime(), textContent);
        }

        return this.content;
    }

    @JRubyMethod
    public IRubyObject document(ThreadContext context) {
        if(this.doc == null) {
            this.doc = fromNodeOrCreate(context,
                    this.node.getOwnerDocument());
        }

        return this.doc;
    }

    @JRubyMethod
    public IRubyObject dup(ThreadContext context) {
        return this.dup_implementation(context, true);
    }

    @JRubyMethod
    public IRubyObject dup(ThreadContext context, IRubyObject depth) {
        boolean deep = depth.convertToInteger().getLongValue() != 0;

        return this.dup_implementation(context, deep);
    }

    protected IRubyObject dup_implementation(ThreadContext context, boolean deep) {
        XmlNode clone;
        try {
            clone = (XmlNode) clone();
        } catch (CloneNotSupportedException e) {
            throw context.getRuntime().newRuntimeError(e.toString());
        }
        if (node == null) throw getRuntime().newRuntimeError("FFFFFFFFFUUUUUUU");
        Node newNode = node.cloneNode(deep);
        clone.node = newNode;
        return clone;
    }

    public static IRubyObject encode_special_chars(ThreadContext context,
                                                   IRubyObject string) {
        String s = rubyStringToString(string);
        String enc = NokogiriHelpers.encodeJavaString(s);
        return context.getRuntime().newString(enc);
    }

    /**
     * Instance method version of the above static method.
     */
    @JRubyMethod(name="encode_special_chars")
    public IRubyObject i_encode_special_chars(ThreadContext context,
                                              IRubyObject string) {
        return encode_special_chars(context, string);
    }

    /**
     * Get the attribute at the given key, <code>rbkey</code>.
     * Assumes that this node has attributes (i.e. that key? returned
     * true). Overridden in XmlElement.
     */
    @JRubyMethod(visibility = Visibility.PRIVATE)
    public IRubyObject get(ThreadContext context, IRubyObject rbkey) {
        return context.getRuntime().getNil();
    }

    /**
     * Returns the owner document, checking if this node is the
     * document, or returns null if there is no owner.
     */
    protected Document getOwnerDocument() {
        if (node.getNodeType() == Node.DOCUMENT_NODE) {
            return (Document) node;
        } else {
            return node.getOwnerDocument();
        }
    }

    @JRubyMethod
    public IRubyObject internal_subset(ThreadContext context) {
        Document document = getOwnerDocument();

        if(document == null) {
            return context.getRuntime().getNil();
        }

        XmlDocument xdoc =
            (XmlDocument) getCachedNodeOrCreate(context.getRuntime(), document);
        IRubyObject xdtd = xdoc.getInternalSubset(context);
        return xdtd;
    }

    @JRubyMethod
    public IRubyObject create_internal_subset(ThreadContext context,
                                              IRubyObject name,
                                              IRubyObject external_id,
                                              IRubyObject system_id) {
        IRubyObject subset = internal_subset(context);
        if (!subset.isNil()) {
            throw context.getRuntime()
                .newRuntimeError("Document already has internal subset");
        }

        Document document = getOwnerDocument();
        if(document == null) {
            return context.getRuntime().getNil();
        }

        XmlDocument xdoc =
            (XmlDocument) getCachedNodeOrCreate(context.getRuntime(), document);
        IRubyObject xdtd = xdoc.createInternalSubset(context, name,
                                                     external_id, system_id);
        return xdtd;
    }

    @JRubyMethod
    public IRubyObject external_subset(ThreadContext context) {
        Document document = getOwnerDocument();

        if(document == null) {
            return context.getRuntime().getNil();
        }

        XmlDocument xdoc =
            (XmlDocument) getCachedNodeOrCreate(context.getRuntime(), document);
        IRubyObject xdtd = xdoc.getExternalSubset(context);
        return xdtd;
    }

    @JRubyMethod
    public IRubyObject create_external_subset(ThreadContext context,
                                              IRubyObject name,
                                              IRubyObject external_id,
                                              IRubyObject system_id) {
        IRubyObject subset = external_subset(context);
        if (!subset.isNil()) {
            throw context.getRuntime()
                .newRuntimeError("Document already has external subset");
        }

        Document document = getOwnerDocument();
        if(document == null) {
            return context.getRuntime().getNil();
        }

        XmlDocument xdoc =
            (XmlDocument) getCachedNodeOrCreate(context.getRuntime(), document);
        IRubyObject xdtd = xdoc.createExternalSubset(context, name,
                                                     external_id, system_id);
        return xdtd;
    }

    /**
     * Test if this node has an attribute named <code>rbkey</code>.
     * Overridden in XmlElement.
     */
    @JRubyMethod(name = "key?")
    public IRubyObject key_p(ThreadContext context, IRubyObject rbkey) {
        return context.getRuntime().getNil();
    }

    @JRubyMethod
    public IRubyObject namespace(ThreadContext context){
        XmlDocument xmlDocument = (XmlDocument) doc;
        NokogiriNamespaceCache nsCache = xmlDocument.getNamespaceCache();
        if (namespace == null) {
            String prefix = node.getPrefix();
            namespace = nsCache.get(prefix == null ? "" : prefix, node.getNamespaceURI());
            if (namespace == null || ((XmlNamespace) namespace).isEmpty()) {
                namespace = context.getRuntime().getNil();
            }
        }

        return namespace;
    }

    /**
     * Return an array of XmlNamespace nodes based on the attributes
     * of this node.
     */
    @JRubyMethod
    public IRubyObject namespace_definitions(ThreadContext context) {
        // don't use namespace_definitions cache anymore since
        // namespaces might be deleted. Reflecting the result of 
        // namesapce removals is complicated, so the cache might not be
        // updated.
        Ruby ruby = context.getRuntime();
        namespace_definitions = ruby.newArray();
        if (doc == null) return namespace_definitions;
        List<XmlNamespace> namespaces = ((XmlDocument)doc).getNamespaceCache().get(node);
        for (XmlNamespace namespace : namespaces) {
            ((RubyArray)namespace_definitions).append(namespace);
        }

        return (RubyArray) this.namespace_definitions;
    }

    /**
     * Return an array of XmlNamespace nodes defined on this node and
     * on any ancestor node.
     */
    @JRubyMethod
    public IRubyObject namespace_scopes(ThreadContext context) {
        RubyArray parentNamespaces;
        RubyArray namespaces = (RubyArray) namespace_definitions(context);

        IRubyObject parent = parent(context);
        if (!parent.isNil()) {
            parentNamespaces = (RubyArray)
                ((XmlNode) parent).namespace_scopes(context);
        } else {
            parentNamespaces = getRuntime().newEmptyArray();
        }

        return parentNamespaces.op_plus(namespaces);
    }

    @JRubyMethod(name="namespaced_key?")
    public IRubyObject namespaced_key_p(ThreadContext context, IRubyObject elementLName, IRubyObject namespaceUri) {
        return this.attribute_with_ns(context, elementLName, namespaceUri).isNil() ?
            context.getRuntime().getFalse() : context.getRuntime().getTrue();
    }

    protected void setContent(IRubyObject content) {
        this.content = content;
        this.node.setTextContent(rubyStringToString(content));
    }

    protected void setContent(String content) {
        getNode().setTextContent(content);
        this.content = null;    // clear cache
    }

    @JRubyMethod(name = "native_content=", visibility = Visibility.PRIVATE)
    public IRubyObject native_content_set(ThreadContext context, IRubyObject content) {
        setContent(content);
        return content;
    }

    /**
     * @param args {IRubyObject io,
     *              IRubyObject encoding,
     *              IRubyObject indentString,
     *              IRubyObject options}
     */
    @JRubyMethod(required=4, visibility=Visibility.PRIVATE)
    public IRubyObject native_write_to(ThreadContext context,
                                       IRubyObject[] args) {

        IRubyObject io = args[0];
        IRubyObject encoding = args[1];
        IRubyObject indentString = args[2];
        IRubyObject options = args[3];

        String encString = encoding.isNil() ? null : encoding.convertToString().asJavaString();

        int opt = (int) options.convertToInteger().getLongValue();

        SaveContext ctx = new SaveContext(context, opt,
                indentString.convertToString().asJavaString(),
                encString);

        saveContent(context, ctx);

        RuntimeHelpers.invoke(context, io, "write",
                              ctx.toRubyString(context.getRuntime()));

        return io;
    }

    @JRubyMethod
    public IRubyObject next_sibling(ThreadContext context) {
        return fromNodeOrCreate(context, node.getNextSibling());
    }

    @JRubyMethod
    public IRubyObject previous_sibling(ThreadContext context) {
        return fromNodeOrCreate(context, node.getPreviousSibling());
    }

    @JRubyMethod(meta = true, rest = true)
    public static IRubyObject new_from_str(ThreadContext context,
                                           IRubyObject cls,
                                           IRubyObject[] args) {
        XmlDocument doc = (XmlDocument) XmlDocument.read_memory(context, args);
        return doc.root(context);
    }

    @JRubyMethod
    public IRubyObject node_name(ThreadContext context) {
        return getNodeName(context);
    }

    @JRubyMethod(name = "node_name=")
    public IRubyObject node_name_set(ThreadContext context, IRubyObject nodeName) {
        String newName = nodeName.convertToString().asJavaString();
        getOwnerDocument().renameNode(node, null, newName);
        setName(nodeName);
        return this;
    }

    @JRubyMethod(name = "[]=")
    public IRubyObject op_aset(ThreadContext context, IRubyObject index, IRubyObject val) {
        return val;
    }

    @JRubyMethod
    public IRubyObject parent(ThreadContext context) {
        /*
         * Check if this node is the root node of the document.
         * If so, parent is the document.
         */
        if (node.getOwnerDocument() != null &&
            node.getOwnerDocument().getDocumentElement() == node) {
            return document(context);
        } else {
            return fromNodeOrCreate(context, node.getParentNode());
        }
    }

    @JRubyMethod
    public IRubyObject path(ThreadContext context) {
        return RubyString.newString(context.getRuntime(), NokogiriHelpers.getNodeCompletePath(this.node));
    }

    @JRubyMethod
    public IRubyObject pointer_id(ThreadContext context) {
        return RubyFixnum.newFixnum(context.getRuntime(), this.node.hashCode());
    }

    @JRubyMethod
    public IRubyObject remove_attribute(ThreadContext context, IRubyObject name) {
        return this;
    }

    @JRubyMethod(visibility=Visibility.PRIVATE)
    public IRubyObject set_namespace(ThreadContext context, IRubyObject namespace) {
        if (namespace.isNil()) {
            if (doc != null) {
                Node n = getNode();
                String prefix = n.getPrefix();
                String href = n.getNamespaceURI();
                ((XmlDocument)doc).getNamespaceCache().remove(prefix == null ? "" : prefix, href);
                n.getOwnerDocument().renameNode(n, null, n.getNodeName());
            }
        } else {
            XmlNamespace ns = (XmlNamespace) namespace;
            String prefix = ns.prefix(context).convertToString().asJavaString();
            String href = ns.href(context).convertToString().asJavaString();

            // Assigning node = ...renameNode() or not seems to make no
            // difference.  Why not? -pmahoney
            node = node.getOwnerDocument().renameNode(node, href, NokogiriHelpers.newQName(prefix, node));
        }
        this.namespace = null;       // clear cache

        return this;
    }

    @JRubyMethod
    public IRubyObject unlink(ThreadContext context) {
        if(node.getParentNode() == null) {
            throw context.getRuntime().newRuntimeError("TYPE: " + node.getNodeType()+ " PARENT NULL");
        } else {
            node.getParentNode().removeChild(node);
        }

        return this;
    }

    /**
     * The C-library simply returns libxml2 magic numbers.  Here we
     * convert Java Xml nodes to the appropriate constant defined in
     * xml/node.rb.
     */
    @JRubyMethod
    public IRubyObject node_type(ThreadContext context) {
        String type;
        switch (node.getNodeType()) {
        case Node.ELEMENT_NODE:
            if (this instanceof XmlElementDecl)
                type = "ELEMENT_DECL";
            else if (this instanceof XmlAttributeDecl)
                type = "ATTRIBUTE_DECL";
            else if (this instanceof XmlEntityDecl)
                type = "ENTITY_DECL";
            else
                type = "ELEMENT_NODE";
            break;
        case Node.ATTRIBUTE_NODE: type = "ATTRIBUTE_NODE"; break;
        case Node.TEXT_NODE: type = "TEXT_NODE"; break;
        case Node.CDATA_SECTION_NODE: type = "CDATA_SECTION_NODE"; break;
        case Node.ENTITY_REFERENCE_NODE: type = "ENTITY_REF_NODE"; break;
        case Node.ENTITY_NODE: type = "ENTITY_NODE"; break;
        case Node.PROCESSING_INSTRUCTION_NODE: type = "PI_NODE"; break;
        case Node.COMMENT_NODE: type = "COMMENT_NODE"; break;
        case Node.DOCUMENT_NODE:
            if (this instanceof HtmlDocument)
                type = "HTML_DOCUMENT_NODE";
            else
                type = "DOCUMENT_NODE";
            break;
        case Node.DOCUMENT_TYPE_NODE: type = "DOCUMENT_TYPE_NODE"; break;
        case Node.DOCUMENT_FRAGMENT_NODE: type = "DOCUMENT_FRAG_NODE"; break;
        case Node.NOTATION_NODE: type = "NOTATION_NODE"; break;
        default:
            return context.getRuntime().newFixnum(0);
        }

        return context.getRuntime()
            .getClassFromPath("Nokogiri::XML::Node")
            .getConstant(type);
    }

    @JRubyMethod
    public IRubyObject line(ThreadContext context) {
        Node root = getOwnerDocument();
        int[] counter = new int[1];
        count(root, counter);
        return RubyFixnum.newFixnum(context.getRuntime(), counter[0]+1);
    }

    private boolean count(Node node, int[] counter) {
        if (node == this.node) {
            return true;
        }
        NodeList list = node.getChildNodes();
        for (int i=0; i<list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Text
                    && ((Text)n).getData().contains("\n")) {
                counter[0] += 1;
            }
            if (count(n, counter)) return true;
        }
        return false;
    }

    @JRubyMethod(name = {"next", "next_element"})
    public IRubyObject next_element(ThreadContext context) {
        Node nextNode = node.getNextSibling();
        Ruby ruby = context.getRuntime();
        if (nextNode == null) return ruby.getNil();
        if (nextNode instanceof Element) {
            return new XmlElement(ruby, (RubyClass)ruby.getClassFromPath("Nokogiri::XML::Element"), nextNode);
        }
        Node deeper = nextNode.getNextSibling();
        if (deeper == null) return ruby.getNil();
        return new XmlElement(ruby, (RubyClass)ruby.getClassFromPath("Nokogiri::XML::Element"), deeper);
    }

    @JRubyMethod
    public IRubyObject previous_element(ThreadContext context) {
        Node prevNode = node.getPreviousSibling();
        Ruby ruby = context.getRuntime();
        if (prevNode == null) return ruby.getNil();
        if (prevNode instanceof Element) {
            return new XmlElement(ruby, (RubyClass)ruby.getClassFromPath("Nokogiri::XML::Element"), prevNode);
        }
        Node shallower = prevNode.getPreviousSibling();
        if (shallower == null) return ruby.getNil();
        return new XmlElement(ruby, (RubyClass)ruby.getClassFromPath("Nokogiri::XML::Element"), shallower);
    }

    protected enum AdoptScheme {
        CHILD, PREV_SIBLING, NEXT_SIBLING, REPLACEMENT;
    }

    /**
     * Adopt XmlNode <code>other</code> into the document of
     * <code>this</code> using the specified scheme.
     */
    protected IRubyObject adoptAs(ThreadContext context, AdoptScheme scheme,
                                  IRubyObject other_) {
        XmlNode other = asXmlNode(context, other_);
        // this.doc might be null since this node can be empty node.
        if (this.doc != null) {
            if (other.doc == null) {
                other.doc = this.doc;
            } else {
              ((XmlDocument)other.doc).setNamespaceCache(((XmlDocument)this.doc).getNamespaceCache());
            }
        }
        IRubyObject nodeOrTags = other;
        Node thisNode = this.getNode();
        Node otherNode = other.getNode();

         try {
            Document doc = thisNode.getOwnerDocument();
            if (doc == null && thisNode instanceof Document) {
                Element e = ((Document)thisNode).getDocumentElement();
                if (e != null && "div".equals(e.getNodeName())) {
                // when empty document is create, <div/> element exists as a document element.
                    doc = ((Document)thisNode).getDocumentElement().getOwnerDocument();
                    scheme = AdoptScheme.REPLACEMENT;
                    thisNode = ((Document)thisNode).getDocumentElement();
                }
            }
            if (doc != null && doc != otherNode.getOwnerDocument()) {
                Node ret = doc.adoptNode(otherNode);
                if (ret == null) {
                    throw context.getRuntime()
                        .newRuntimeError("Failed to take ownership of node");
                }
            }

            Node parent = thisNode.getParentNode();

            switch (scheme) {
            case CHILD:
                Node[] children = adoptAsChild(context, thisNode, otherNode);
                if (children.length == 1 && otherNode == children[0]) {
                    break;
                } else {
                    nodeOrTags = nodeArrayToRubyArray(context.getRuntime(), children);
                }
                break;
            case PREV_SIBLING:
                adoptAsPrevSibling(context, parent, thisNode, otherNode);
                break;
            case NEXT_SIBLING:
                adoptAsNextSibling(context, parent, thisNode, otherNode);
                break;
            case REPLACEMENT:
                adoptAsReplacement(context, parent, thisNode, otherNode);
                break;
            }
         } catch (Exception e) {
             throw context.getRuntime().newRuntimeError(e.toString());
         }

        if (otherNode.getNodeType() == Node.TEXT_NODE) {
            coalesceTextNodes(context, other);
        }

        relink_namespace(context);
        // post_add_child(context, this, other);

        return nodeOrTags;
    }

    protected Node[] adoptAsChild(ThreadContext context, Node parent,
                                Node otherNode) {
        if (hasTemporaryRoot(parent, otherNode)) {
            return adoptRealChildrenAsChild(context, parent, otherNode);
        }
        
        /*
         * This is a bit of a hack.  C-Nokogiri allows adding a bare
         * text node as the root element.  Java (and XML spec?) does
         * not.  So we wrap the text node in an element.
         */
        if (parent.getNodeType() == Node.DOCUMENT_NODE &&
            otherNode.getNodeType() == Node.TEXT_NODE) {
            Element e = ((Document)parent).createElement("text");
            e.appendChild(otherNode);
            otherNode = e;
        }
        addNamespaceURIIfNeeded(otherNode);
        parent.appendChild(otherNode);
        Node[] nodes = new Node[1];
        nodes[0] = otherNode;
        return nodes;
    }
    
    private boolean hasTemporaryRoot(Node parent, Node child) {
        if (parent.getNodeType() == Node.DOCUMENT_FRAGMENT_NODE
                && child.getNodeName().equals(NokogiriNamespaceContext.NOKOGIRI_TEMPORARY_ROOT_TAG)) {
            return true;
        }
        return false;
    }
    
    private Node[] adoptRealChildrenAsChild(ThreadContext context, Node parent, Node otherNode) {
        NodeList children = otherNode.getChildNodes();
        int length = children.getLength();
        List<Node> nodes = new ArrayList<Node>();
        for (int i=0; i < length; i++) {
            nodes.add(children.item(i).cloneNode(true));
        }
        //cut out leading and trailing whitespaces
        if (nodes.get(0).getNodeType() == Node.TEXT_NODE && nodes.get(0).getTextContent().trim().length() == 0) {
            nodes.remove(0);
        }
        int lastIndex = nodes.size() - 1;
        if (nodes.get(lastIndex).getNodeType() == Node.TEXT_NODE && nodes.get(lastIndex).getTextContent().trim().length() == 0) {
            nodes.remove(lastIndex);
        }
        
        Node[] nodeArray = new Node[nodes.size()];
        for (int i=0; i < nodeArray.length; i++) {
            Node child = nodes.get(i);
            addNamespaceURIIfNeeded(child);
            parent.appendChild(child);
            nodeArray[i] = child;
        }
        return nodeArray;
    }

    private void addNamespaceURIIfNeeded(Node child) {
        if (this instanceof XmlDocumentFragment && ((XmlDocumentFragment)this).getFragmentContext() != null) {
            XmlElement fragmentContext = ((XmlDocumentFragment)this).getFragmentContext();
            String namespace_uri = fragmentContext.getNode().getNamespaceURI();
            if (namespace_uri != null && namespace_uri.length() > 0) {
                this.getNode().getOwnerDocument().renameNode(child, namespace_uri, child.getNodeName());
            }
        }
    }

    protected void adoptAsPrevSibling(ThreadContext context,
                                      Node parent,
                                      Node thisNode, Node otherNode) {
        if (parent == null) {
            /* I'm not sure what do do here...  A node with no
             * parent can't exactly have a 'sibling', so we make
             * otherNode parentless also. */
            if (otherNode.getParentNode() != null)
                otherNode.getParentNode().removeChild(otherNode);

            return;
        }

        parent.insertBefore(otherNode, thisNode);
    }

    protected void adoptAsNextSibling(ThreadContext context,
                                      Node parent,
                                      Node thisNode, Node otherNode) {
        if (parent == null) {
            /* I'm not sure what do do here...  A node with no
             * parent can't exactly have a 'sibling', so we make
             * otherNode parentless also. */
            if (otherNode.getParentNode() != null)
                otherNode.getParentNode().removeChild(otherNode);

            return;
        }

        Node nextSib = thisNode.getNextSibling();
        if (nextSib != null) {
            parent.insertBefore(otherNode, nextSib);
        } else {
            parent.appendChild(otherNode);
        }
    }

    protected void adoptAsReplacement(ThreadContext context,
                                      Node parentNode,
                                      Node thisNode, Node otherNode) {
        if (parentNode == null) {
            /* nothing to replace? */
            return;
        }

        try {
            parentNode.replaceChild(otherNode, thisNode);
            otherNode.getOwnerDocument().renameNode(otherNode, thisNode.getNamespaceURI(), otherNode.getNodeName());
        } catch (Exception e) {
            String prefix = "could not replace child: ";
            throw context.getRuntime().newRuntimeError(prefix + e.toString());
        }
    }

    /**
     * Replace <code>this</code> with <code>other</code>.
     */
    @JRubyMethod
    public IRubyObject replace_node(ThreadContext context,
                                    IRubyObject other) {
        return adoptAs(context, AdoptScheme.REPLACEMENT, other);
    }

    /**
     * Add <code>other</code> as a sibling before <code>this</code>.
     */
    @JRubyMethod
    public IRubyObject add_previous_sibling_node(ThreadContext context,
                                                 IRubyObject other) {
        return adoptAs(context, AdoptScheme.PREV_SIBLING, other);
    }

    /**
     * Add <code>other</code> as a sibling after <code>this</code>.
     */
    @JRubyMethod
    public IRubyObject add_next_sibling_node(ThreadContext context,
                                             IRubyObject other) {
        return adoptAs(context, AdoptScheme.NEXT_SIBLING, other);
    }
}
