/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.ws.security.wss4j.policyhandlers;

import java.io.IOException;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.callback.CallbackHandler;
import javax.xml.namespace.QName;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPHeader;
import javax.xml.soap.SOAPMessage;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.apache.cxf.Bus;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.common.classloader.ClassLoaderUtils;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.helpers.MapNamespaceContext;
import org.apache.cxf.resource.ResourceManager;
import org.apache.cxf.ws.policy.AssertionInfo;
import org.apache.cxf.ws.policy.AssertionInfoMap;
import org.apache.cxf.ws.policy.PolicyAssertion;
import org.apache.cxf.ws.policy.PolicyConstants;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.policy.SP12Constants;
import org.apache.cxf.ws.security.policy.SPConstants;
import org.apache.cxf.ws.security.policy.model.AsymmetricBinding;
import org.apache.cxf.ws.security.policy.model.Binding;
import org.apache.cxf.ws.security.policy.model.Header;
import org.apache.cxf.ws.security.policy.model.IssuedToken;
import org.apache.cxf.ws.security.policy.model.Layout;
import org.apache.cxf.ws.security.policy.model.SignedEncryptedElements;
import org.apache.cxf.ws.security.policy.model.SignedEncryptedParts;
import org.apache.cxf.ws.security.policy.model.SupportingToken;
import org.apache.cxf.ws.security.policy.model.SymmetricBinding;
import org.apache.cxf.ws.security.policy.model.Token;
import org.apache.cxf.ws.security.policy.model.TokenWrapper;
import org.apache.cxf.ws.security.policy.model.UsernameToken;
import org.apache.cxf.ws.security.policy.model.Wss10;
import org.apache.cxf.ws.security.policy.model.Wss11;
import org.apache.cxf.ws.security.policy.model.X509Token;
import org.apache.cxf.wsdl.WSDLConstants;
import org.apache.velocity.util.ClassUtils;
import org.apache.ws.security.WSConstants;
import org.apache.ws.security.WSEncryptionPart;
import org.apache.ws.security.WSPasswordCallback;
import org.apache.ws.security.WSSecurityEngineResult;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.WSUsernameTokenPrincipal;
import org.apache.ws.security.components.crypto.Crypto;
import org.apache.ws.security.components.crypto.CryptoFactory;
import org.apache.ws.security.handler.WSHandlerConstants;
import org.apache.ws.security.handler.WSHandlerResult;
import org.apache.ws.security.message.WSSecBase;
import org.apache.ws.security.message.WSSecEncryptedKey;
import org.apache.ws.security.message.WSSecHeader;
import org.apache.ws.security.message.WSSecSignature;
import org.apache.ws.security.message.WSSecSignatureConfirmation;
import org.apache.ws.security.message.WSSecTimestamp;
import org.apache.ws.security.message.WSSecUsernameToken;
import org.apache.ws.security.util.WSSecurityUtil;

/**
 * 
 */
public class BindingBuilder {
    private static final Logger LOG = LogUtils.getL7dLogger(BindingBuilder.class); 
    
    protected SOAPMessage saaj;
    protected WSSecHeader secHeader;
    protected AssertionInfoMap aim;
    protected Binding binding;
    protected SoapMessage message;
    protected WSSecTimestamp timestampEl;
    protected String mainSigId;
    
    protected Set<String> encryptedTokensIdList = new HashSet<String>();

    protected Map<Token, WSSecBase> endEncSuppTokMap;
    protected Map<Token, WSSecBase> endSuppTokMap;
    protected Map<Token, WSSecBase> sgndEndEncSuppTokMap;
    protected Map<Token, WSSecBase> sgndEndSuppTokMap;
    
    protected Vector<byte[]> signatures = new Vector<byte[]>();

    
    public BindingBuilder(Binding binding,
                           SOAPMessage saaj,
                           WSSecHeader secHeader,
                           AssertionInfoMap aim,
                           SoapMessage message) {
        this.binding = binding;
        this.aim = aim;
        this.secHeader = secHeader;
        this.saaj = saaj;
        this.message = message;
        message.getExchange().put(WSHandlerConstants.SEND_SIGV, signatures);
    }

    
    protected boolean isRequestor() {
        return Boolean.TRUE.equals(message.containsKey(
            org.apache.cxf.message.Message.REQUESTOR_ROLE));
    }  
    protected void policyNotAsserted(PolicyAssertion assertion, Exception reason) {
        LOG.log(Level.INFO, "Not asserting " + assertion.getName(), reason);
        Collection<AssertionInfo> ais;
        ais = aim.get(assertion.getName());
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                if (ai.getAssertion() == assertion) {
                    ai.setNotAsserted(reason.getMessage());
                }
            }
        }
    }
    protected void policyNotAsserted(PolicyAssertion assertion, String reason) {
        LOG.log(Level.INFO, "Not asserting " + assertion.getName(), reason);
        Collection<AssertionInfo> ais;
        ais = aim.get(assertion.getName());
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                if (ai.getAssertion() == assertion) {
                    ai.setNotAsserted(reason);
                }
            }
        }
    }
    protected void policyAsserted(PolicyAssertion assertion) {
        LOG.log(Level.INFO, "Asserting " + assertion.getName());
        Collection<AssertionInfo> ais;
        ais = aim.get(assertion.getName());
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                if (ai.getAssertion() == assertion) {
                    ai.setAsserted(true);
                }
            }
        }
    }
    protected void policyAsserted(QName n) {
        Collection<AssertionInfo> ais = aim.getAssertionInfo(n);
        if (ais != null && !ais.isEmpty()) {
            for (AssertionInfo ai : ais) {
                ai.setAsserted(true);
            }
        }
    }
    
    protected PolicyAssertion findPolicy(QName n) {
        Collection<AssertionInfo> ais = aim.getAssertionInfo(n);
        if (ais != null && !ais.isEmpty()) {
            return ais.iterator().next().getAssertion();
        }
        return null;
    } 
    
    protected void insertAfter(Element child, Element parent, Element sib) {
        if (sib.getNextSibling() == null) {
            parent.appendChild(child);
        } else {
            parent.insertBefore(child, sib.getNextSibling());
        }
    }
        
    protected WSSecTimestamp createTimestamp() {
        Collection<AssertionInfo> ais;
        ais = aim.get(SP12Constants.INCLUDE_TIMESTAMP);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                timestampEl = new WSSecTimestamp();
                timestampEl.prepare(saaj.getSOAPPart());
                ai.setAsserted(true);
            }                    
        }
        timestampEl.prependToHeader(secHeader);
        return timestampEl;
    }
    
    protected WSSecTimestamp handleLayout(WSSecTimestamp timestamp) {
        Collection<AssertionInfo> ais;
        ais = aim.get(SP12Constants.LAYOUT);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                Layout layout = (Layout)ai.getAssertion();
                ai.setAsserted(true);
                if (SPConstants.Layout.LaxTimestampLast == layout.getValue()) {
                    if (timestamp == null) {
                        ai.setNotAsserted(SPConstants.Layout.LaxTimestampLast + " requires a timestamp");
                    } else {
                        ai.setAsserted(true);
                        Element el = timestamp.getElement();
                        secHeader.getSecurityHeader().removeChild(el);
                        secHeader.getSecurityHeader().appendChild(el);
                    }
                } else if (SPConstants.Layout.LaxTimestampFirst == layout.getValue()) {
                    if (timestamp == null) {
                        ai.setNotAsserted(SPConstants.Layout.LaxTimestampLast + " requires a timestamp");
                    } else {
                        Element el = timestamp.getElement();
                        secHeader.getSecurityHeader().removeChild(el);
                        secHeader.getSecurityHeader().insertBefore(el,
                                                                   secHeader.getSecurityHeader()
                                                                       .getFirstChild());
                    }
                }
            }                    
        }
        return timestamp;
    }
    
    protected Map<Token, WSSecBase> handleSupportingTokens(SupportingToken suppTokens) {
        Map<Token, WSSecBase> ret = new HashMap<Token, WSSecBase>();
        if (suppTokens == null) {
            return ret;
        }
        for (Token token : suppTokens.getTokens()) {
            if (token instanceof UsernameToken) {
                WSSecUsernameToken utBuilder = addUsernameToken((UsernameToken)token);
                if (utBuilder != null) {
                    utBuilder.prepare(saaj.getSOAPPart());
                    utBuilder.appendToHeader(secHeader);
                    ret.put(token, utBuilder);
                    encryptedTokensIdList.add(utBuilder.getId());
                }
            } else if (token instanceof IssuedToken && isRequestor()) {
                //ws-trust stuff.......
                //REVISIT
                policyNotAsserted(token, "Issued token not yet supported");
            } else if (token instanceof X509Token) {

                //We have to use a cert
                //Prepare X509 signature
                WSSecSignature sig = getSignatureBuider(suppTokens, token);
                Element bstElem = sig.getBinarySecurityTokenElement();
                if (bstElem != null) {
                    sig.appendBSTElementToHeader(secHeader);
                }
                if (suppTokens.isEncryptedToken()) {
                    encryptedTokensIdList.add(sig.getBSTTokenId());
                }
                ret.put(token, sig);
            }         
            
        }
        return ret;
    }
    
    protected void addSignatureParts(Map<Token, WSSecBase> tokenMap,
                                                         List<WSEncryptionPart> sigParts) {
        
        for (Map.Entry<Token, WSSecBase> entry : tokenMap.entrySet()) {
            
            Object tempTok =  entry.getValue();
            WSEncryptionPart part = null;
            
            if (tempTok instanceof WSSecSignature) {
                WSSecSignature tempSig = (WSSecSignature) tempTok;
                if (tempSig.getBSTTokenId() != null) {
                    part = new WSEncryptionPart(tempSig.getBSTTokenId());
                }
            } else {
                policyNotAsserted(entry.getKey(), "UnsupportedTokenInSupportingToken");  
            }
            if (part != null) {
                sigParts.add(part);
            }
        }
    }

    
    protected WSSecUsernameToken addUsernameToken(UsernameToken token) {
        
        AssertionInfo info = null;
        Collection<AssertionInfo> ais = aim.getAssertionInfo(token.getName());
        for (AssertionInfo ai : ais) {
            if (ai.getAssertion() == token) {
                info = ai;
                if (!isRequestor()) {
                    info.setAsserted(true);
                    return null;
                }
            }
        }
        
        String userName = (String)message.getContextualProperty(SecurityConstants.USERNAME);
        
        if (!StringUtils.isEmpty(userName)) {
            // If NoPassword property is set we don't need to set the password
            if (token.isNoPassword()) {
                WSSecUsernameToken utBuilder = new WSSecUsernameToken();
                utBuilder.setUserInfo(userName, null);
                utBuilder.setPasswordType(null);
                info.setAsserted(true);
                return utBuilder;
            }
            
            String password = (String)message.getContextualProperty(SecurityConstants.PASSWORD);
            if (StringUtils.isEmpty(password)) {
                password = getPassword(userName, token, WSPasswordCallback.USERNAME_TOKEN);
            }
            
            if (!StringUtils.isEmpty(password)) {
                //If the password is available then build the token
                WSSecUsernameToken utBuilder = new WSSecUsernameToken();
                if (token.isHashPassword()) {
                    utBuilder.setPasswordType(WSConstants.PASSWORD_DIGEST);  
                } else {
                    utBuilder.setPasswordType(WSConstants.PASSWORD_TEXT);
                }
                
                utBuilder.setUserInfo(userName, password);
                info.setAsserted(true);
                return utBuilder;
            } else {
                info.setNotAsserted("No password available");
            }
        } else {
            info.setNotAsserted("No username available");
        }
        return null;
    }
    public String getPassword(String userName, PolicyAssertion info, int type) {
      //Then try to get the password from the given callback handler
        Object o = message.getContextualProperty(SecurityConstants.CALLBACK_HANDLER);
    
        CallbackHandler handler = null;
        if (o instanceof CallbackHandler) {
            handler = (CallbackHandler)o;
        } else if (o instanceof String) {
            try {
                handler = (CallbackHandler)ClassUtils.getNewInstance(o.toString());
            } catch (Exception e) {
                handler = null;
            }
        }
        if (handler == null) {
            policyNotAsserted(info, "No callback handler and no password available");
            return null;
        }
        
        WSPasswordCallback[] cb = {new WSPasswordCallback(userName,
                                                          type)};
        try {
            handler.handle(cb);
        } catch (Exception e) {
            policyNotAsserted(info, e);
        }
        
        //get the password
        return cb[0].getPassword();
    }

    public String addWsuIdToElement(Element elem) {
        String id;
        
        //first try to get the Id attr
        Attr idAttr = elem.getAttributeNode("Id");
        if (idAttr == null) {
            //then try the wsu:Id value
            idAttr = elem.getAttributeNodeNS(PolicyConstants.WSU_NAMESPACE_URI, "Id");
        }
        
        if (idAttr != null) {
            id = idAttr.getValue();
        } else {
            //Add an id
            id = "Id-" + elem.hashCode();
            String pfx = elem.lookupPrefix(PolicyConstants.WSU_NAMESPACE_URI);
            boolean found = !StringUtils.isEmpty(pfx);
            int cnt = 0;
            while (StringUtils.isEmpty(pfx)) {
                pfx = "wsu" + (cnt == 0 ? "" : cnt);
                if (!StringUtils.isEmpty(elem.lookupNamespaceURI(pfx))) {
                    pfx = null;
                    cnt++;
                }
            }
            if (!found) {
                idAttr = elem.getOwnerDocument().createAttributeNS(WSDLConstants.NS_XMLNS, "xmlns:" + pfx);
                idAttr.setValue(PolicyConstants.WSU_NAMESPACE_URI);
                elem.setAttributeNodeNS(idAttr);
            }
            idAttr = elem.getOwnerDocument().createAttributeNS(PolicyConstants.WSU_NAMESPACE_URI, 
                                                               pfx + ":Id");
            idAttr.setValue(id);
            elem.setAttributeNodeNS(idAttr);
        }
        
        return id;
    }

    public Vector<WSEncryptionPart> getEncryptedParts() 
        throws SOAPException {
        
        boolean isBody = false;
        
        SignedEncryptedParts parts = null;
        SignedEncryptedElements elements = null;
        
        Collection<AssertionInfo> ais = aim.getAssertionInfo(SP12Constants.ENCRYPTED_PARTS);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                parts = (SignedEncryptedParts)ai.getAssertion();
                ai.setAsserted(true);
            }            
        }
        ais = aim.getAssertionInfo(SP12Constants.ENCRYPTED_ELEMENTS);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                elements = (SignedEncryptedElements)ai.getAssertion();
                ai.setAsserted(true);
            }            
        }
        
        List<WSEncryptionPart> signedParts = new ArrayList<WSEncryptionPart>();
        if (parts != null) {
            isBody = parts.isBody();
            for (Header head : parts.getHeaders()) {
                WSEncryptionPart wep = new WSEncryptionPart(head.getName(),
                                                            head.getNamespace(),
                                                            "Content");
                signedParts.add(wep);
            }
        }
    
        
        return getPartsAndElements(false, 
                                   isBody,
                                   signedParts,
                                   elements == null ? null : elements.getXPathExpressions(),
                                   elements == null ? null : elements.getDeclaredNamespaces());
    }    
    
    public Vector<WSEncryptionPart> getSignedParts() 
        throws SOAPException {
        
        boolean isSignBody = false;
        
        SignedEncryptedParts parts = null;
        SignedEncryptedElements elements = null;
        
        Collection<AssertionInfo> ais = aim.getAssertionInfo(SP12Constants.SIGNED_PARTS);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                parts = (SignedEncryptedParts)ai.getAssertion();
                ai.setAsserted(true);
            }            
        }
        ais = aim.getAssertionInfo(SP12Constants.SIGNED_ELEMENTS);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                elements = (SignedEncryptedElements)ai.getAssertion();
                ai.setAsserted(true);
            }            
        }
        
        List<WSEncryptionPart> signedParts = new ArrayList<WSEncryptionPart>();
        if (parts != null) {
            isSignBody = parts.isBody();
            for (Header head : parts.getHeaders()) {
                WSEncryptionPart wep = new WSEncryptionPart(head.getName(),
                                                            head.getNamespace(),
                                                            "Content");
                signedParts.add(wep);
            }
        }

        
        return getPartsAndElements(true, 
                                   isSignBody,
                                   signedParts,
                                   elements == null ? null : elements.getXPathExpressions(),
                                   elements == null ? null : elements.getDeclaredNamespaces());
    }
    public Vector<WSEncryptionPart> getPartsAndElements(boolean sign, 
                                                    boolean includeBody,
                                                    List<WSEncryptionPart> parts,
                                                    List<String> xpaths, 
                                                    Map<String, String> namespaces) 
        throws SOAPException {
        
        Vector<WSEncryptionPart> result = new Vector<WSEncryptionPart>();
        List<Element> found = new ArrayList<Element>();
        if (includeBody) {
            if (sign) {
                result.add(new WSEncryptionPart(addWsuIdToElement(saaj.getSOAPBody())));
            } else {
                result.add(new WSEncryptionPart(addWsuIdToElement(saaj.getSOAPBody()),
                                                "Content", WSConstants.PART_TYPE_BODY));
            }
            found.add(saaj.getSOAPBody());
        }
        SOAPHeader header = saaj.getSOAPHeader();
        for (WSEncryptionPart part : parts) {
            if (StringUtils.isEmpty(part.getName())) {
                //an entire namespace
                Element el = DOMUtils.getFirstElement(header);
                while (el != null) {
                    if (part.getNamespace().equals(el.getNamespaceURI())
                        && !found.contains(el)) {
                        found.add(el);
                        
                        if (sign) {
                            result.add(new WSEncryptionPart(el.getLocalName(), 
                                                            part.getNamespace(),
                                                            "Content"));
                        } else {
                            WSEncryptionPart encryptedHeader 
                                = new WSEncryptionPart(el.getLocalName(),
                                                       part.getNamespace(), 
                                                       "Element",
                                                       WSConstants.PART_TYPE_HEADER);
                            String wsuId = el.getAttributeNS(WSConstants.WSU_NS, "Id");
                            
                            if (!StringUtils.isEmpty(wsuId)) {
                                encryptedHeader.setEncId(wsuId);
                            }
                            result.add(encryptedHeader);
                        }
                    }
                }
                el = DOMUtils.getNextElement(el);
            } else {
                Element el = DOMUtils.getFirstElement(header);
                while (el != null) {
                    if (part.getName().equals(el.getLocalName())
                        && part.getNamespace().equals(el.getNamespaceURI())
                        && !found.contains(el)) {
                        found.add(el);          
                        part.setType(WSConstants.PART_TYPE_HEADER);
                        String wsuId = el.getAttributeNS(WSConstants.WSU_NS, "Id");
                        
                        if (!StringUtils.isEmpty(wsuId)) {
                            part.setEncId(wsuId);
                        }
                        
                        result.add(part);
                    }
                    el = DOMUtils.getNextElement(el);
                }
            }
        }
        if (xpaths != null && !xpaths.isEmpty()) {
            XPathFactory factory = XPathFactory.newInstance();
            for (String expression : xpaths) {
                XPath xpath = factory.newXPath();
                if (namespaces != null) {
                    xpath.setNamespaceContext(new MapNamespaceContext(namespaces));
                }
                try {
                    NodeList list = (NodeList)xpath.evaluate(expression, saaj.getSOAPPart().getEnvelope(),
                                                   XPathConstants.NODESET);
                    for (int x = 0; x < list.getLength(); x++) {
                        Element el = (Element)list.item(x);
                        if (sign) {
                            result.add(new WSEncryptionPart(el.getLocalName(),
                                                            el.getNamespaceURI(), 
                                                            "Content"));
                        } else {
                            WSEncryptionPart encryptedElem = new WSEncryptionPart(el.getLocalName(),
                                                                                  el.getNamespaceURI(),
                                                                                  "Element");
                            String wsuId = el.getAttributeNS(WSConstants.WSU_NS, "Id");
                            
                            if (!StringUtils.isEmpty(wsuId)) {
                                encryptedElem.setEncId(wsuId);
                            }
                            result.add(encryptedElem);
                        }
                    }
                } catch (XPathExpressionException e) {
                    //REVISIT!!!!
                }
            }
        }
        return result;
    }
    
    
    protected WSSecEncryptedKey getEncryptedKeyBuilder(TokenWrapper wrapper, 
                                                       Token token) throws WSSecurityException {
        WSSecEncryptedKey encrKey = new WSSecEncryptedKey();
        
        setKeyIdentifierType(encrKey, wrapper, token);
        setEncryptionUser(encrKey, wrapper, false);
        encrKey.setKeySize(binding.getAlgorithmSuite().getMaximumSymmetricKeyLength());
        encrKey.setKeyEncAlgo(binding.getAlgorithmSuite().getAsymmetricKeyWrap());
        
        encrKey.prepare(saaj.getSOAPPart(), getEncryptionCrypto(wrapper));
        
        return encrKey;
    }
    public Crypto getSignatureCrypto(TokenWrapper wrapper) {
        return getCrypto(wrapper, true);
    }
    public Crypto getEncryptionCrypto(TokenWrapper wrapper) {
        return getCrypto(wrapper, false);
    }
    public Crypto getCrypto(TokenWrapper wrapper, boolean sign) {
        Crypto crypto = (Crypto)message.getContextualProperty(sign 
                                                      ? SecurityConstants.SIGNATURE_CRYPTO 
                                                      : SecurityConstants.ENCRYPT_CRYPTO);
        if (crypto != null) {
            return crypto;
        }
        
        
        Object o = message.getContextualProperty(sign 
                                                 ? SecurityConstants.SIGNATURE_PROPERTIES 
                                                 : SecurityConstants.ENCRYPT_PROPERTIES);
        Properties properties = null;
        if (o instanceof Properties) {
            properties = (Properties)o;
        } else if (o instanceof String) {
            ResourceManager rm = message.getExchange().get(Bus.class).getExtension(ResourceManager.class);
            URL url = rm.resolveResource((String)o, URL.class);
            try {
                if (url == null) {
                    url = ClassLoaderUtils.getResource((String)o, this.getClass());
                }
                if (url != null) {
                    properties = new Properties();
                    properties.load(url.openStream());
                } else {
                    policyNotAsserted(wrapper, "Could not find properties file " + o);
                }
            } catch (IOException e) {
                policyNotAsserted(wrapper, e);
            }
        } else if (o instanceof URL) {
            properties = new Properties();
            try {
                properties.load(((URL)o).openStream());
            } catch (IOException e) {
                policyNotAsserted(wrapper, e);
            }            
        }
        
        if (properties != null) {
            return CryptoFactory.getInstance(properties);
        }
        return null;
    }
    
    public void setKeyIdentifierType(WSSecBase secBase, TokenWrapper wrapper, Token token) {
        
        if (token.getInclusion() == SPConstants.IncludeTokenType.INCLUDE_TOKEN_NEVER) {
            boolean tokenTypeSet = false;
            
            if (token instanceof X509Token) {
                X509Token x509Token = (X509Token)token;
                if (x509Token.isRequireIssuerSerialReference()) {
                    secBase.setKeyIdentifierType(WSConstants.ISSUER_SERIAL);
                    tokenTypeSet = true;
                } else if (x509Token.isRequireKeyIdentifierReference()) {
                    secBase.setKeyIdentifierType(WSConstants.SKI_KEY_IDENTIFIER);
                    tokenTypeSet = true;
                } else if (x509Token.isRequireThumbprintReference()) {
                    secBase.setKeyIdentifierType(WSConstants.THUMBPRINT_IDENTIFIER);
                    tokenTypeSet = true;
                }
            } 
            
            if (!tokenTypeSet) {
                policyAsserted(token);
                policyAsserted(wrapper);
                
                Wss10 wss = getWss10();
                policyAsserted(wss);
                if (wss.isMustSupportRefKeyIdentifier()) {
                    secBase.setKeyIdentifierType(WSConstants.SKI_KEY_IDENTIFIER);
                } else if (wss.isMustSupportRefIssuerSerial()) {
                    secBase.setKeyIdentifierType(WSConstants.ISSUER_SERIAL);
                } else if (wss instanceof Wss11
                                && ((Wss11) wss).isMustSupportRefThumbprint()) {
                    secBase.setKeyIdentifierType(WSConstants.THUMBPRINT_IDENTIFIER);
                }
            }
            
        } else {
            policyAsserted(token);
            policyAsserted(wrapper);
            secBase.setKeyIdentifierType(WSConstants.BST_DIRECT_REFERENCE);
        }
    }
    public void setEncryptionUser(WSSecEncryptedKey encrKeyBuilder, TokenWrapper token, boolean sign) {
        String encrUser = (String)message.getContextualProperty(sign 
                                                                ? SecurityConstants.USERNAME
                                                                : SecurityConstants.ENCRYPT_USERNAME);
        if (encrUser == null || "".equals(encrUser)) {
            policyNotAsserted(token, "No " + (sign ? "signature" : "encryption") + " username found.");
        }
        if (encrUser.equals(WSHandlerConstants.USE_REQ_SIG_CERT)) {
            Object resultsObj = message.getExchange().getInMessage().get(WSHandlerConstants.RECV_RESULTS);
            if (resultsObj != null) {
                encrKeyBuilder.setUseThisCert(getReqSigCert((Vector)resultsObj));
                 
                //TODO This is a hack, this should not come under USE_REQ_SIG_CERT
                if (encrKeyBuilder.isCertSet()) {
                    encrKeyBuilder.setUserInfo(getUsername((Vector)resultsObj));
                }
            } else {
                policyNotAsserted(token, "No security results in incoming message");
            }
        } else {
            encrKeyBuilder.setUserInfo(encrUser);
        }
    }
    private static X509Certificate getReqSigCert(Vector results) {
        /*
        * Scan the results for a matching actor. Use results only if the
        * receiving Actor and the sending Actor match.
        */
        for (int i = 0; i < results.size(); i++) {
            WSHandlerResult rResult =
                    (WSHandlerResult) results.get(i);

            Vector wsSecEngineResults = rResult.getResults();
            /*
            * Scan the results for the first Signature action. Use the
            * certificate of this Signature to set the certificate for the
            * encryption action :-).
            */
            for (int j = 0; j < wsSecEngineResults.size(); j++) {
                WSSecurityEngineResult wser =
                        (WSSecurityEngineResult) wsSecEngineResults.get(j);
                Integer actInt = (Integer)wser.get(WSSecurityEngineResult.TAG_ACTION);
                if (actInt.intValue() == WSConstants.SIGN) {
                    return (X509Certificate)wser.get(WSSecurityEngineResult.TAG_X509_CERTIFICATE);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Scan through <code>WSHandlerResult<code> vector for a Username token and return
     * the username if a Username Token found 
     * @param results
     * @return
     */
    
    public static String getUsername(Vector results) {
        /*
         * Scan the results for a matching actor. Use results only if the
         * receiving Actor and the sending Actor match.
         */
        for (int i = 0; i < results.size(); i++) {
            WSHandlerResult rResult =
                     (WSHandlerResult) results.get(i);

            Vector wsSecEngineResults = rResult.getResults();
            /*
             * Scan the results for a username token. Use the username
             * of this token to set the alias for the encryption user
             */
            for (int j = 0; j < wsSecEngineResults.size(); j++) {
                WSSecurityEngineResult wser =
                         (WSSecurityEngineResult) wsSecEngineResults.get(j);
                Integer actInt = (Integer)wser.get(WSSecurityEngineResult.TAG_ACTION);
                if (actInt.intValue() == WSConstants.UT) {
                    WSUsernameTokenPrincipal principal 
                        = (WSUsernameTokenPrincipal)wser.get(WSSecurityEngineResult.TAG_PRINCIPAL);
                    return principal.getName();
                }
            }
        }
         
        return null;
    }
    protected Wss10 getWss10() {
        Collection<AssertionInfo> ais = aim.getAssertionInfo(SP12Constants.WSS10);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                return (Wss10)ai.getAssertion();
            }            
        }        
        ais = aim.getAssertionInfo(SP12Constants.WSS11);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                return (Wss10)ai.getAssertion();
            }            
        }   
        return null;
    }

    private void checkForX509PkiPath(WSSecSignature sig, Token token) {
        if (token instanceof X509Token) {
            X509Token x509Token = (X509Token) token;
            if (x509Token.getTokenVersionAndType().equals(SPConstants.WSS_X509_PKI_PATH_V1_TOKEN10)
                    || x509Token.getTokenVersionAndType().equals(SPConstants.WSS_X509_PKI_PATH_V1_TOKEN11)) {
                sig.setUseSingleCertificate(false);
            }
        }
    }
    protected WSSecSignature getSignatureBuider(TokenWrapper wrapper, Token token) {
        WSSecSignature sig = new WSSecSignature();
        checkForX509PkiPath(sig, token);
        
        setKeyIdentifierType(sig, wrapper, token);
        
        String user = (String)message.getContextualProperty(SecurityConstants.USERNAME);
        if (StringUtils.isEmpty(user)) {
            policyNotAsserted(token, "No signature username found.");
        }

        String password = getPassword(user, token, WSPasswordCallback.SIGNATURE);
        if (StringUtils.isEmpty(password)) {
            policyNotAsserted(token, "No password found.");
        }

        sig.setUserInfo(user, password);
        sig.setSignatureAlgorithm(binding.getAlgorithmSuite().getAsymmetricSignature());
        sig.setSigCanonicalization(binding.getAlgorithmSuite().getInclusiveC14n());
        
        try {
            sig.prepare(saaj.getSOAPPart(),
                        getSignatureCrypto(wrapper), 
                        secHeader);
        } catch (WSSecurityException e) {
            policyNotAsserted(token, e);
        }
        
        return sig;
    }

    protected void doEndorsedSignatures(Map<Token, WSSecBase> tokenMap,
                                          boolean isTokenProtection) {
        
        for (Map.Entry<Token, WSSecBase> ent : tokenMap.entrySet()) {
            WSSecBase tempTok = ent.getValue();
            
            Vector<WSEncryptionPart> sigParts = new Vector<WSEncryptionPart>();
            sigParts.add(new WSEncryptionPart(mainSigId));
            
            if (tempTok instanceof WSSecSignature) {
                WSSecSignature sig = (WSSecSignature)tempTok;
                if (isTokenProtection && sig.getBSTTokenId() != null) {
                    sigParts.add(new WSEncryptionPart(sig.getBSTTokenId()));
                }
                try {
                    sig.addReferencesToSign(sigParts, secHeader);
                    sig.computeSignature();
                    sig.appendToHeader(secHeader);
                    
                    signatures.add(sig.getSignatureValue());
                } catch (WSSecurityException e) {
                    policyNotAsserted(ent.getKey(), e);
                }
                
            }
        } 
    }
    
    protected void addSupportingTokens(Vector<WSEncryptionPart> sigs) {
        
        SupportingToken sgndSuppTokens = 
            (SupportingToken)findPolicy(SP12Constants.SIGNED_SUPPORTING_TOKENS);
        
        Map<Token, WSSecBase> sigSuppTokMap = this.handleSupportingTokens(sgndSuppTokens);           
        
        SupportingToken endSuppTokens = 
            (SupportingToken)findPolicy(SP12Constants.ENDORSING_SUPPORTING_TOKENS);
        
        endSuppTokMap = this.handleSupportingTokens(endSuppTokens);
        
        SupportingToken sgndEndSuppTokens 
            = (SupportingToken)findPolicy(SP12Constants.SIGNED_ENDORSING_SUPPORTING_TOKENS);
        sgndEndSuppTokMap = this.handleSupportingTokens(sgndEndSuppTokens);
        
        SupportingToken sgndEncryptedSuppTokens 
            = (SupportingToken)findPolicy(SP12Constants.SIGNED_ENCRYPTED_SUPPORTING_TOKENS);
        Map<Token, WSSecBase> sgndEncSuppTokMap 
            = this.handleSupportingTokens(sgndEncryptedSuppTokens);
        
        SupportingToken endorsingEncryptedSuppTokens 
            = (SupportingToken)findPolicy(SP12Constants.ENDORSING_ENCRYPTED_SUPPORTING_TOKENS);
        endEncSuppTokMap 
            = this.handleSupportingTokens(endorsingEncryptedSuppTokens);
        
        SupportingToken sgndEndEncSuppTokens 
            = (SupportingToken)findPolicy(SP12Constants.SIGNED_ENDORSING_ENCRYPTED_SUPPORTING_TOKENS);
        sgndEndEncSuppTokMap 
            = this.handleSupportingTokens(sgndEndEncSuppTokens);
        
        SupportingToken supportingToks 
            = (SupportingToken)findPolicy(SP12Constants.SUPPORTING_TOKENS);
        this.handleSupportingTokens(supportingToks);
        
        SupportingToken encryptedSupportingToks 
            = (SupportingToken)findPolicy(SP12Constants.ENCRYPTED_SUPPORTING_TOKENS);
        this.handleSupportingTokens(encryptedSupportingToks);
    
        //Setup signature parts
        addSignatureParts(sigSuppTokMap, sigs);
        addSignatureParts(sgndEncSuppTokMap, sigs);
        addSignatureParts(sgndEndSuppTokMap, sigs);
        addSignatureParts(sgndEndEncSuppTokMap, sigs);

    }
    

    protected void doEndorse() {
        boolean tokenProtect = false;
        if (binding instanceof AsymmetricBinding) {
            tokenProtect = ((AsymmetricBinding)binding).isTokenProtection();
        } else if (binding instanceof SymmetricBinding) {
            tokenProtect = ((SymmetricBinding)binding).isTokenProtection();
        }
        // Adding the endorsing encrypted supporting tokens to endorsing supporting tokens
        endSuppTokMap.putAll(endEncSuppTokMap);
        // Do endorsed signatures
        doEndorsedSignatures(endSuppTokMap, tokenProtect);

        //Adding the signed endorsed encrypted tokens to signed endorsed supporting tokens
        sgndEndSuppTokMap.putAll(sgndEndEncSuppTokMap);
        // Do signed endorsing signatures
        doEndorsedSignatures(sgndEndSuppTokMap, tokenProtect);
    } 

    protected void addSignatureConfirmation(Vector<WSEncryptionPart> sigParts) {
        Wss10 wss10 = getWss10();
        
        if (!(wss10 instanceof Wss11) 
            || !((Wss11)wss10).isRequireSignatureConfirmation()) {
            //If we don't require sig confirmation simply go back :-)
            return;
        }
        
        Vector results = (Vector)message.getExchange().getInMessage().get(WSHandlerConstants.RECV_RESULTS);
        /*
         * loop over all results gathered by all handlers in the chain. For each
         * handler result get the various actions. After that loop we have all
         * signature results in the signatureActions vector
         */
        Vector signatureActions = new Vector();
        for (int i = 0; i < results.size(); i++) {
            WSHandlerResult wshResult = (WSHandlerResult) results.get(i);

            WSSecurityUtil.fetchAllActionResults(wshResult.getResults(),
                    WSConstants.SIGN, signatureActions);
            WSSecurityUtil.fetchAllActionResults(wshResult.getResults(),
                    WSConstants.ST_SIGNED, signatureActions);
            WSSecurityUtil.fetchAllActionResults(wshResult.getResults(),
                    WSConstants.UT_SIGN, signatureActions);
        }
        
        // prepare a SignatureConfirmation token
        WSSecSignatureConfirmation wsc = new WSSecSignatureConfirmation();
        if (signatureActions.size() > 0) {
            for (int i = 0; i < signatureActions.size(); i++) {
                WSSecurityEngineResult wsr = (WSSecurityEngineResult) signatureActions
                        .get(i);
                byte[] sigVal = (byte[]) wsr.get(WSSecurityEngineResult.TAG_SIGNATURE_VALUE);
                wsc.setSignatureValue(sigVal);
                wsc.prepare(saaj.getSOAPPart());
                wsc.prependToHeader(secHeader);
                if (sigParts != null) {
                    sigParts.add(new WSEncryptionPart(wsc.getId()));
                }
            }
        } else {
            //No Sig value
            wsc.prepare(saaj.getSOAPPart());
            wsc.prependToHeader(secHeader);
            if (sigParts != null) {
                sigParts.add(new WSEncryptionPart(wsc.getId()));
            }
        }
    }
}
