package main

import (
	"bytes"
	"encoding/binary"
	"testing"
)

// buildIPPBody construit un body IPP minimal avec un groupe operation-
// attributes contenant les attributs fournis. Helper utilisé par les
// tests pour générer des fixtures Send-Document / Print-Job.
func buildIPPBody(opID uint16, reqID uint32, attrs []struct {
	tag   byte
	name  string
	value []byte
}) []byte {
	var buf bytes.Buffer
	_ = binary.Write(&buf, binary.BigEndian, uint16(0x0200)) // version 2.0
	_ = binary.Write(&buf, binary.BigEndian, opID)
	_ = binary.Write(&buf, binary.BigEndian, reqID)
	buf.WriteByte(tagOperationAttr)
	for _, a := range attrs {
		writeAttr(&buf, a.tag, a.name, a.value)
	}
	buf.WriteByte(tagEndOfAttrs)
	return buf.Bytes()
}

func TestExtractJobID_Found(t *testing.T) {
	body := buildIPPBody(opSendDocument, 1, []struct {
		tag   byte
		name  string
		value []byte
	}{
		{tagCharset, "attributes-charset", []byte("utf-8")},
		{tagNaturalLanguage, "attributes-natural-language", []byte("en-us")},
		{tagURI, "printer-uri", []byte("ipps://printer/ipp/print")},
		{tagInteger, "job-id", []byte{0x00, 0x00, 0x00, 0x2a}}, // 42
	})
	id, ok := ExtractJobID(body)
	if !ok {
		t.Fatal("expected ExtractJobID to return ok=true")
	}
	if id != 42 {
		t.Fatalf("expected id=42, got %d", id)
	}
}

func TestExtractJobID_NotFound(t *testing.T) {
	body := buildIPPBody(opSendDocument, 1, []struct {
		tag   byte
		name  string
		value []byte
	}{
		{tagCharset, "attributes-charset", []byte("utf-8")},
		{tagURI, "printer-uri", []byte("ipps://printer/ipp/print")},
	})
	id, ok := ExtractJobID(body)
	if ok {
		t.Fatalf("expected ExtractJobID to return ok=false, got id=%d", id)
	}
}

func TestExtractJobID_TooShort(t *testing.T) {
	_, ok := ExtractJobID([]byte{0x01, 0x02}) // 2 bytes — below 8-byte header
	if ok {
		t.Fatal("expected ExtractJobID on truncated body to return ok=false")
	}
}

func TestExtractPrintJobAttrs_FromSendDocument(t *testing.T) {
	// Send-Document avec print-color-mode=color + sides=two-sided-long-edge.
	body := buildIPPBody(opSendDocument, 2, []struct {
		tag   byte
		name  string
		value []byte
	}{
		{tagCharset, "attributes-charset", []byte("utf-8")},
		{tagInteger, "job-id", []byte{0x00, 0x00, 0x00, 0x01}},
		{tagKeyword, "print-color-mode", []byte("color")},
		{tagKeyword, "sides", []byte("two-sided-long-edge")},
	})
	color, duplex := ExtractPrintJobAttrs(body)
	if !color {
		t.Error("expected color=true for print-color-mode=color")
	}
	if !duplex {
		t.Error("expected duplex=true for sides=two-sided-long-edge")
	}
}
