import traceback
try:
    import ingest_core
    ingest_core.main()
    print("MAIN_DONE")
except Exception as e:
    with open("ingest_err.log", "w", encoding="utf-8") as f:
        traceback.print_exc(file=f)
    print("CAUGHT", repr(e))
