package main

// Parser/builder IPP minimal (RFC 8011 §4.1) — uniquement ce dont
// claudine-proxy a besoin :
//   - parser une réponse IPP pour extraire job-uri / job-id / job-state /
//     job-impressions-completed
//   - construire une requête Get-Job-Attributes
//
// Format wire IPP :
//   header (8 octets) :
//     0-1 : version-number (major, minor)
//     2-3 : operation-id (request) ou status-code (response)
//     4-7 : request-id
//   attribute groups :
//     [delim-tag (0x01..0x05)] [attribute]+
//   end :
//     0x03 (end-of-attributes)
//   attribute :
//     [value-tag] [name-len:2] [name] [value-len:2] [value]
//     name vide = continuation de l'attribut multi-valué précédent

import (
	"bytes"
	"encoding/binary"
	"errors"
)

// Delimiter tags.
const (
	tagOperationAttr = 0x01
	tagJobAttr       = 0x02
	tagEndOfAttrs    = 0x03
	tagPrinterAttr   = 0x04
	tagUnsupported   = 0x05
)

// Value tags (sous-ensemble utile).
const (
	tagInteger             = 0x21
	tagBoolean             = 0x22
	tagEnum                = 0x23
	tagOctetString         = 0x30
	tagTextWithoutLanguage = 0x41
	tagNameWithoutLanguage = 0x42
	tagKeyword             = 0x44
	tagURI                 = 0x45
	tagURIScheme           = 0x46
	tagCharset             = 0x47
	tagNaturalLanguage     = 0x48
	tagMimeMediaType       = 0x49
)

// IPP operation IDs we use.
const (
	opGetJobAttributes uint16 = 0x0009
)

type attribute struct {
	valueTag byte
	name     string
	value    []byte
}

// ParseHeader extrait les 8 octets de header IPP et retourne la slice du
// reste (groupes d'attributs).
func ParseHeader(body []byte) (version uint16, opOrStatus uint16, reqID uint32, attrs []byte, err error) {
	if len(body) < 8 {
		return 0, 0, 0, nil, errors.New("ipp body too short")
	}
	version = binary.BigEndian.Uint16(body[0:2])
	opOrStatus = binary.BigEndian.Uint16(body[2:4])
	reqID = binary.BigEndian.Uint32(body[4:8])
	attrs = body[8:]
	return
}

// parseAttributes scanne les groupes d'attributs IPP, en aplatissant la
// liste (ignore les delimiter tags et propage le name pour les
// continuations multi-valuées). S'arrête à 0x03.
func parseAttributes(body []byte) []attribute {
	var out []attribute
	var lastName string
	i := 0
	for i < len(body) {
		tag := body[i]
		i++
		if tag == tagEndOfAttrs {
			break
		}
		if tag >= tagOperationAttr && tag <= tagUnsupported {
			// delimiter tag — passer à l'attribut suivant
			lastName = ""
			continue
		}
		// value tag
		if i+2 > len(body) {
			break
		}
		nameLen := int(binary.BigEndian.Uint16(body[i : i+2]))
		i += 2
		if i+nameLen > len(body) {
			break
		}
		name := string(body[i : i+nameLen])
		i += nameLen
		if i+2 > len(body) {
			break
		}
		valueLen := int(binary.BigEndian.Uint16(body[i : i+2]))
		i += 2
		if i+valueLen > len(body) {
			break
		}
		value := body[i : i+valueLen]
		i += valueLen
		if name == "" {
			name = lastName
		} else {
			lastName = name
		}
		out = append(out, attribute{valueTag: tag, name: name, value: value})
	}
	return out
}

func attrInt(attrs []attribute, name string) (int, bool) {
	for _, a := range attrs {
		if a.name != name {
			continue
		}
		if (a.valueTag == tagInteger || a.valueTag == tagEnum) && len(a.value) >= 4 {
			return int(int32(binary.BigEndian.Uint32(a.value[0:4]))), true
		}
	}
	return 0, false
}

func attrString(attrs []attribute, name string) (string, bool) {
	for _, a := range attrs {
		if a.name == name {
			return string(a.value), true
		}
	}
	return "", false
}

// ExtractJobURI lit la réponse IPP d'un Print-Job pour récupérer le job-uri
// que la Kyocera a assigné, ainsi que le job-id (id IPP, pas le UUID Spring).
func ExtractJobURI(body []byte) (string, int, error) {
	_, _, _, attrs, err := ParseHeader(body)
	if err != nil {
		return "", 0, err
	}
	parsed := parseAttributes(attrs)
	uri, ok := attrString(parsed, "job-uri")
	if !ok {
		return "", 0, errors.New("job-uri not found")
	}
	id, _ := attrInt(parsed, "job-id")
	return uri, id, nil
}

// ExtractJobState lit la réponse IPP d'un Get-Job-Attributes.
func ExtractJobState(body []byte) (state int, impressionsCompleted int, err error) {
	_, _, _, attrs, err := ParseHeader(body)
	if err != nil {
		return 0, 0, err
	}
	parsed := parseAttributes(attrs)
	state, _ = attrInt(parsed, "job-state")
	impressionsCompleted, _ = attrInt(parsed, "job-impressions-completed")
	return state, impressionsCompleted, nil
}

// BuildGetJobAttributes construit une requête IPP Get-Job-Attributes pour
// le job-uri donné, demandant explicitement job-state et
// job-impressions-completed.
func BuildGetJobAttributes(jobURI string, reqID uint32) []byte {
	var buf bytes.Buffer
	// Header : version 2.0, op Get-Job-Attributes, request-id
	_ = binary.Write(&buf, binary.BigEndian, uint16(0x0200))
	_ = binary.Write(&buf, binary.BigEndian, opGetJobAttributes)
	_ = binary.Write(&buf, binary.BigEndian, reqID)
	// Groupe operation-attributes
	buf.WriteByte(tagOperationAttr)
	writeAttr(&buf, tagCharset, "attributes-charset", []byte("utf-8"))
	writeAttr(&buf, tagNaturalLanguage, "attributes-natural-language", []byte("en-us"))
	writeAttr(&buf, tagURI, "job-uri", []byte(jobURI))
	// requested-attributes (multi-valué, name vide pour continuation)
	writeAttr(&buf, tagKeyword, "requested-attributes", []byte("job-state"))
	writeAttr(&buf, tagKeyword, "", []byte("job-impressions-completed"))
	// Fin
	buf.WriteByte(tagEndOfAttrs)
	return buf.Bytes()
}

func writeAttr(buf *bytes.Buffer, tag byte, name string, value []byte) {
	buf.WriteByte(tag)
	_ = binary.Write(buf, binary.BigEndian, uint16(len(name)))
	buf.WriteString(name)
	_ = binary.Write(buf, binary.BigEndian, uint16(len(value)))
	buf.Write(value)
}
