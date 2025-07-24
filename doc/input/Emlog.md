# Using an Emlog smart meter as the input source

An Emlog smart meter can be accessed using the generic-http input source. To access the Emlog smart meter, use the
following configuration in the `uni-meter.conf` file:

```hocon
uni-meter {
  output = "uni-meter.output-devices.shelly-pro3em"
  
  input = "uni-meter.input-devices.generic-http"

  input-devices {
    generic-http {
      url = "http://192.168.x.x/pages/getinformation.php?export&meterindex=1" 

      power-phase-mode = "tri-phase"
      energy-phase-mode = "mono-phase"

      channels = [{
        type = "json"
        channel = "energy-consumption-total"
        json-path = "$.Zaehlerstand_Bezug.Stand180"
        scale = 1
      },{
        type = "json"
        channel = "energy-production-total"
        json-path = "$.Zaehlerstand_Lieferung.Stand280"
        scale = 1
      },{
        type = "json"
        channel = "power-l1"
        json-path = "$.Wirkleistung_Bezug.Leistung171"
      },{
        type = "json"
        channel = "power-production-l1"
        json-path = "$.Wirkleistung_Lieferung.Leistung271"
      },{
        type = "json"
        channel = "power-l2"
        json-path = "$.Wirkleistung_Bezug.Leistung172"
      },{
        type = "json"
        channel = "power-production-l2"
        json-path = "$.Wirkleistung_Lieferung.Leistung272"
      },{
        type = "json"
        channel = "power-l3"
        json-path = "$.Wirkleistung_Bezug.Leistung173"
      },{
        type = "json"
        channel = "power-production-l3"
        json-path = "$.Wirkleistung_Lieferung.Leistung273"
      }]
    }
  }
}
```